// Reads Google search results from the page (like Inspect). Returns a JSON string:
// {blocked, url, results:[{t: title, u: url, s: snippet text}]}
// Anchors with an <h3> inside are Google's organic results; that structure has been stable for years.
(function () {
  var text = document.body ? document.body.innerText : '';
  var blocked = location.pathname.indexOf('/sorry/') === 0 ||
    !!document.querySelector('#captcha-form, form[action*="sorry"], iframe[src*="recaptcha"]') ||
    /unusual traffic from your computer network|not a robot/i.test(text) ||
    location.hostname.indexOf('consent.') === 0;
  var out = [], seen = {};
  var heads = document.querySelectorAll('a h3, a [role="heading"]');
  for (var i = 0; i < heads.length && out.length < 8; i++) {
    var h = heads[i], a = h.closest('a');
    if (!a || !a.href) continue;
    var href = a.href;
    try {  // Google's redirect links: /url?q=<target>
      var parsed = new URL(href);
      var target = parsed.pathname === '/url' && (parsed.searchParams.get('q') || parsed.searchParams.get('url'));
      if (target && /^https?:/.test(target)) href = target;
    } catch (e) {}
    if (/^https?:\/\/([a-z]+\.)?google\.[^\/]+\//.test(href) || seen[href]) continue;  // skip Google's own links
    seen[href] = 1;
    // Walk up to the result's container and take its text as the snippet source.
    var box = a, depth = 0;
    while (box.parentElement && depth < 6 && (box.innerText || '').length < (h.innerText || '').length + 60) {
      box = box.parentElement; depth++;
    }
    out.push({ t: (h.innerText || '').trim(), u: href, s: (box.innerText || '').substring(0, 600) });
  }
  return JSON.stringify({ blocked: blocked, url: location.href, results: out });
})();
