import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from fakeserver import Fake  # noqa: E402

from hy import agent as agent_mod  # noqa: E402
from hy.agent import APPROVE_ABORT, APPROVE_ALWAYS, APPROVE_NO, APPROVE_YES, Agent  # noqa: E402
from hy.client import Client, ServerError  # noqa: E402
from hy.tools import Tools  # noqa: E402
from hy.ui import UI, Renderer  # noqa: E402

CFG = {"mode": "ask", "ctx": 8192, "max_steps": 5, "temperature": 0.2}


class QuietUI(UI):
    """A UI that records approvals instead of reading the keyboard."""

    def __init__(self, answers=()):
        super().__init__(out=io.StringIO(), interactive=False)
        self.answers = list(answers)
        self.asked = []

    def approve(self, level, title, detail):
        self.asked.append((level, title, detail))
        return self.answers.pop(0) if self.answers else APPROVE_NO

    def text(self):
        return self.out.getvalue()


class ClientTest(unittest.TestCase):
    def test_streams_text_and_chunked_tool_calls(self):
        f = Fake([{"content": "Hello there, friend!"}, {"tool_calls": [("run", {"command": "ls -la"})]}])
        try:
            c = Client("127.0.0.1", f.port, "m")
            self.assertEqual(c.alive(), "fake-model")
            got = []
            r = c.chat([{"role": "user", "content": "hi"}], on_text=got.append)
            self.assertEqual(r["content"], "Hello there, friend!")
            self.assertEqual("".join(got), "Hello there, friend!")
            self.assertEqual(r["finish"], "stop")
            r = c.chat([{"role": "user", "content": "x"}], tools=[{"type": "function"}])
            self.assertEqual(r["tool_calls"][0]["name"], "run")
            self.assertEqual(json.loads(r["tool_calls"][0]["arguments"]), {"command": "ls -la"})
            self.assertEqual(f.requests[1]["tools"], [{"type": "function"}])
        finally:
            f.close()

    def test_down_server(self):
        c = Client("127.0.0.1", 1, "m")
        self.assertIsNone(c.alive())
        with self.assertRaises(ServerError):
            c.chat([{"role": "user", "content": "x"}])


class AgentTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()

    def run_agent(self, replies, answers=(), mode="ask", prompt="do it"):
        f = Fake(replies)
        self.addCleanup(f.close)
        ui = QuietUI(answers)
        a = Agent(Client("127.0.0.1", f.port, "m"), ui, Tools(self.d, backup_dir=None), {**CFG, "mode": mode})
        out = a.ask(prompt)
        return a, ui, f, out

    def test_plain_answer(self):
        a, ui, f, out = self.run_agent([{"content": "The answer is 4."}])
        self.assertEqual(out, "The answer is 4.")
        self.assertIn("The answer is 4.", ui.text())
        self.assertEqual(a.messages[-1], {"role": "assistant", "content": "The answer is 4."})
        self.assertEqual(f.requests[0]["messages"][0]["role"], "system")
        self.assertEqual([t["function"]["name"] for t in f.requests[0]["tools"]], ["run", "write", "edit", "fetch"])

    def test_safe_command_runs_without_asking_and_result_goes_back(self):
        open(os.path.join(self.d, "marker.txt"), "w").write("x")
        a, ui, f, out = self.run_agent([{"tool_calls": [("run", {"command": "ls"})]}, {"content": "There is marker.txt."}])
        self.assertEqual(ui.asked, [])
        tool_msg = f.requests[1]["messages"][-1]
        self.assertEqual(tool_msg["role"], "tool")
        self.assertIn("marker.txt", tool_msg["content"])
        self.assertEqual(f.requests[1]["messages"][-2]["tool_calls"][0]["function"]["name"], "run")
        self.assertEqual(out, "There is marker.txt.")

    def test_changes_ask_and_yes_runs(self):
        a, ui, f, out = self.run_agent([{"tool_calls": [("write", {"path": "n.txt", "content": "hi"})]}, {"content": "Done."}], answers=[APPROVE_YES])
        self.assertEqual(len(ui.asked), 1)
        self.assertEqual(open(os.path.join(self.d, "n.txt")).read(), "hi")

    def test_declined_changes_nothing_and_tells_model(self):
        a, ui, f, out = self.run_agent([{"tool_calls": [("write", {"path": "n.txt", "content": "hi"})]}, {"content": "Okay, skipped."}], answers=[APPROVE_NO])
        self.assertFalse(os.path.exists(os.path.join(self.d, "n.txt")))
        self.assertIn("declined", f.requests[1]["messages"][-1]["content"])

    def test_abort_stops_everything(self):
        a, ui, f, out = self.run_agent(
            [{"tool_calls": [("write", {"path": "a.txt", "content": "1"}), ("write", {"path": "b.txt", "content": "2"})]}], answers=[APPROVE_ABORT])
        self.assertFalse(os.path.exists(os.path.join(self.d, "a.txt")))
        self.assertFalse(os.path.exists(os.path.join(self.d, "b.txt")))
        ids = [m.get("tool_call_id") for m in a.messages if m["role"] == "tool"]
        self.assertEqual(sorted(ids), ["c0", "c1"])  # every call has an answer, so history stays valid

    def test_always_remembers_for_normal_but_not_risky(self):
        replies = [{"tool_calls": [("run", {"command": "python3 -c 'print(1)'"})]}, {"tool_calls": [("run", {"command": "python3 -c 'print(2)'"})]},
                   {"tool_calls": [("run", {"command": "rm nothing"})]}, {"tool_calls": [("run", {"command": "rm nothing"})]}, {"content": "ok"}]
        a, ui, f, out = self.run_agent(replies, answers=[APPROVE_ALWAYS, APPROVE_ALWAYS, APPROVE_YES])
        kinds = [x[0] for x in ui.asked]
        self.assertEqual(kinds, ["normal", "risky", "risky"])  # second python3 ran without asking; both rm asked

    def test_blocked_is_refused_in_every_mode(self):
        for mode in ("ask", "auto"):
            a, ui, f, out = self.run_agent([{"tool_calls": [("run", {"command": "rm -rf ~"})]}, {"content": "ok"}], mode=mode, answers=[APPROVE_YES])
            self.assertEqual(ui.asked, [])
            self.assertIn("refused", f.requests[1]["messages"][-1]["content"])

    def test_auto_mode_runs_normal_but_asks_risky(self):
        replies = [{"tool_calls": [("run", {"command": "python3 -c 'print(5)'"})]}, {"tool_calls": [("run", {"command": "ssh x ls"})]}, {"content": "ok"}]
        a, ui, f, out = self.run_agent(replies, mode="auto", answers=[APPROVE_NO])
        self.assertEqual([x[0] for x in ui.asked], ["risky"])
        self.assertIn("5", f.requests[1]["messages"][-1]["content"])

    def test_readonly_mode_refuses_changes(self):
        a, ui, f, out = self.run_agent([{"tool_calls": [("write", {"path": "n.txt", "content": "x"})]}, {"content": "ok"}], mode="readonly")
        self.assertFalse(os.path.exists(os.path.join(self.d, "n.txt")))
        self.assertIn("read-only", f.requests[1]["messages"][-1]["content"])

    def test_step_limit(self):
        replies = [{"tool_calls": [("run", {"command": "echo hi"})]}] * 8
        a, ui, f, out = self.run_agent(replies)
        self.assertIn("stopped after 5 steps", ui.text())

    def test_bad_json_arguments_reported_to_model(self):
        f = Fake([{"content": ""}, {"content": "recovered"}])
        self.addCleanup(f.close)
        ui = QuietUI()
        a = Agent(Client("127.0.0.1", f.port, "m"), ui, Tools(self.d), CFG)
        a.messages.append({"role": "user", "content": "x"})
        a._run_calls([{"id": "z", "name": "run", "arguments": "{not json"}])
        self.assertIn("not valid JSON", a.messages[-1]["content"])

    def test_salvages_text_tool_calls(self):
        txt = "Let me look.<tool_call><function=run><parameter=command>\necho salvaged\n</parameter></function></tool_call>"
        a, ui, f, out = self.run_agent([{"content": txt}, {"content": "done"}])
        self.assertNotIn("<tool_call", ui.text())
        self.assertIn("salvaged", f.requests[1]["messages"][-1]["content"])

    def test_server_error_keeps_history_valid(self):
        ui = QuietUI()
        a = Agent(Client("127.0.0.1", 1, "m"), ui, Tools(self.d), CFG)
        self.assertEqual(a.ask("hello"), "")
        self.assertEqual([m["role"] for m in a.messages], ["system"])
        self.assertIn("lost the AI engine", ui.text())

    def test_old_tool_output_is_trimmed_when_context_fills(self):
        ui = QuietUI()
        a = Agent(Client("127.0.0.1", 1, "m"), ui, Tools(self.d), {**CFG, "ctx": 2000})
        for i in range(6):
            a.messages += [{"role": "user", "content": f"q{i}"}, {"role": "assistant", "content": "", "tool_calls": [
                {"id": f"t{i}", "type": "function", "function": {"name": "run", "arguments": "{}"}}]},
                {"role": "tool", "tool_call_id": f"t{i}", "content": "x" * 1500}]
        a._compact()
        self.assertLessEqual(agent_mod.estimate_tokens(a.messages), 2000 * 0.72 + 50)
        self.assertEqual(a.messages[0]["role"], "system")
        self.assertTrue(any("q5" == m.get("content") for m in a.messages))  # newest request survives


class WarmUpTest(unittest.TestCase):
    def test_real_requests_continue_the_warmup_conversation(self):
        f = Fake([{"content": "Ready"}, {"content": "Hi!"}, {"content": "Fine."}])
        self.addCleanup(f.close)
        ui = QuietUI()
        a = Agent(Client("127.0.0.1", f.port, "m"), ui, Tools(tempfile.mkdtemp()), CFG)
        a.start_priming()
        self.assertEqual(a.ask("hello"), "Hi!")  # waits for the warm-up first
        warm, real = f.requests[0]["messages"], f.requests[1]["messages"]
        self.assertEqual(real[: len(warm)], warm)  # the engine can only reuse an exact start
        self.assertEqual([m["role"] for m in real], ["system", "user", "assistant", "user"])
        self.assertEqual(real[2]["content"], "Ready")
        self.assertEqual(f.requests[0]["temperature"], 0)
        a.ask("again")
        self.assertEqual(f.requests[2]["messages"][: len(real) + 1], real + [{"role": "assistant", "content": "Hi!"}])

    def test_clear_warms_up_again(self):
        f = Fake([{"content": "Ready"}, {"content": "Ready"}, {"content": "ok"}])
        self.addCleanup(f.close)
        a = Agent(Client("127.0.0.1", f.port, "m"), QuietUI(), Tools(tempfile.mkdtemp()), CFG)
        a.start_priming()
        a.wait_primed()
        a.clear()
        a.ask("x")
        self.assertEqual([m["role"] for m in f.requests[2]["messages"]], ["system", "user", "assistant", "user"])

    def test_failed_warmup_is_harmless(self):
        a = Agent(Client("127.0.0.1", 1, "m"), QuietUI(), Tools(tempfile.mkdtemp()), CFG)
        a.start_priming()
        a.wait_primed()
        self.assertEqual([m["role"] for m in a.messages], ["system"])


class RendererTest(unittest.TestCase):
    def render(self, text, chunk=1):
        out = []
        r = Renderer(out.append)
        for i in range(0, len(text), chunk):
            r.feed(text[i:i + chunk])
        r.finish()
        return "".join(out)

    def test_plain_when_not_a_terminal(self):
        text = "# Title\n- one\n- two\n```py\nx = 1\n```\nbye -- ok\n"
        for chunk in (1, 3, 100):
            out = self.render(text, chunk)
            self.assertIn("Title", out)
            self.assertIn("one", out)
            self.assertIn("x = 1", out)
            self.assertNotIn("# Title", out)
            self.assertIn("bye -- ok", out)


if __name__ == "__main__":
    unittest.main()
