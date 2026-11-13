/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.opentv.R
import app.opentv.ui.design.SetteeColors

/**
 * The page the phone sees.
 *
 * Deliberately one self-contained document that asks nothing of the outside world: no
 * frameworks, no analytics, and Settee's fonts come from the TV itself ([PairingFonts]). The phone
 * is talking to a TV on the local network, which may have no route to the internet at all, and a
 * page that needs a CDN to render would simply fail.
 */
object PairingPage {

    fun form(token: String, error: String?): String = page(
        token = token,
        content = """
        <h1>Set up Settee</h1>
        <p class="sub">Filling this in here beats using the remote.</p>

        ${if (error != null) "<div class=\"error\">${escape(error)}</div>" else ""}

        <form id="form" method="post" action="/submit" autocomplete="off">
          <input type="hidden" name="t" value="${escape(token)}">

          <div class="seg">
            <label class="segbtn">
              <input type="radio" name="kind" value="xtream" checked onchange="toggle()">
              <span>Xtream login</span>
            </label>
            <label class="segbtn">
              <input type="radio" name="kind" value="m3u" onchange="toggle()">
              <span>M3U playlist</span>
            </label>
          </div>

          <label for="name">Name <span class="opt">optional</span></label>
          <input id="name" name="name" type="text" placeholder="Living room provider">

          <label for="url" id="url-label">Server address</label>
          <input id="url" name="url" type="url" inputmode="url" required
                 placeholder="http://example.com:8080"
                 autocapitalize="off" autocorrect="off" spellcheck="false">
          <p class="hint" id="url-hint">
            You can paste the whole line from your provider, and the extra bits get trimmed.
          </p>

          <div id="creds">
            <label for="username">Username</label>
            <input id="username" name="username" type="text"
                   autocapitalize="off" autocorrect="off" spellcheck="false">

            <label for="password">Password</label>
            <input id="password" name="password" type="password">
          </div>

          <label for="epg">Guide (XMLTV) URL <span class="opt">optional</span></label>
          <input id="epg" name="epg" type="url" inputmode="url"
                 autocapitalize="off" autocorrect="off" spellcheck="false">
          <p class="hint">Leave blank to use your provider's guide automatically.</p>

          <hr>

          <label for="code">Code shown on the TV</label>
          <input id="code" name="code" type="text" inputmode="numeric" pattern="[0-9]*"
                 maxlength="6" required class="code" placeholder="000000"
                 autocomplete="one-time-code">
          <p class="hint">This is what proves it's you standing in front of the telly.</p>

          <button id="send" type="submit">Send to TV</button>
        </form>

        <script>
          function toggle() {
            var isM3u = document.querySelector('input[name=kind]:checked').value === 'm3u';
            document.getElementById('creds').style.display = isM3u ? 'none' : 'block';
            document.getElementById('url-label').textContent =
              isM3u ? 'Playlist URL' : 'Server address';
            document.getElementById('url-hint').textContent = isM3u
              ? 'The full http link to your .m3u or .m3u8 file.'
              : "You can paste the whole line from your provider.";
            document.getElementById('username').required = !isM3u;
            document.getElementById('password').required = !isM3u;
          }
          toggle();
          // The TV loads the channels before it answers, which takes a while on a big provider, so
          // the button says it's working and can't be pressed twice.
          document.getElementById('form').addEventListener('submit', function () {
            var send = document.getElementById('send');
            send.disabled = true;
            send.innerHTML = '<span class="spin" aria-hidden="true"></span>Sending to your TV\u2026';
          });
        </script>
        """.trimIndent(),
    )

    /**
     * The TMDB Key's paste box, with how to get a key under it: the steps in full from Settings, or
     * behind "How do I get one?" during Onboarding ([onboarding]), where the key is optional and the
     * page says so. [error] is why the TV turned the last key down, a sentence and a detail line.
     */
    fun tmdbKey(token: String, text: (Int) -> String, error: Error?, onboarding: Boolean): String {
        val steps = """
            <ol class="steps">
              <li><a href="$TMDB_SIGNUP" target="_blank" rel="noopener">${escape(
            text(R.string.pairing_phone_tmdb_step1),
        )}</a></li>
              <li><a href="$TMDB_API_SETTINGS" target="_blank" rel="noopener">${escape(
            text(R.string.pairing_phone_tmdb_step2),
        )}</a></li>
              <li>${escape(text(R.string.pairing_phone_tmdb_step3))}</li>
              <li>${escape(text(R.string.pairing_phone_tmdb_step4))}</li>
            </ol>
        """.trimIndent()
        val errorBox = error?.let {
            "<div class=\"error\">${escape(it.sentence)}" +
                (if (it.detail.isNotBlank()) "<span class=\"detail\">${escape(it.detail)}</span>" else "") +
                "</div>"
        }.orEmpty()
        return page(
            token = token,
            content = """
            <h1>${escape(text(R.string.onboarding_tmdb_title))}</h1>
            <p class="sub">${escape(
                text(if (onboarding) R.string.onboarding_tmdb_help else R.string.pairing_phone_tmdb_sub),
            )}</p>

            $errorBox

            <form id="form" method="post" action="/key" autocomplete="off">
              <input type="hidden" name="t" value="${escape(token)}">
              <label for="key">${escape(text(R.string.pairing_phone_tmdb_label))}</label>
              <input id="key" name="key" type="text" required
                     autocapitalize="off" autocorrect="off" spellcheck="false">
              <button id="send" type="submit">${escape(text(R.string.pairing_phone_tmdb_send))}</button>
            </form>

            ${if (onboarding) skipButton(token, text) else ""}

            ${if (onboarding) {
                "<details><summary>${escape(
                    text(R.string.pairing_phone_tmdb_how),
                )}</summary>$steps</details>"
            } else {
                steps
            }}

            <script>
              // The TV checks the key with TMDB before it answers, so the button says it's working.
              document.getElementById('form').addEventListener('submit', function () {
                var send = document.getElementById('send');
                send.disabled = true;
                send.innerHTML = '<span class="spin" aria-hidden="true"></span>' + send.textContent;
              });
            </script>
            """.trimIndent(),
        )
    }

    /**
     * Signing the TV in to OpenSubtitles: username and password, which go to the TV and from there
     * only to OpenSubtitles, with where to make a free account. Setup's ([onboarding]) has Not now.
     */
    fun openSubtitles(token: String, text: (Int) -> String, error: Error?, onboarding: Boolean): String = page(
        token = token,
        content = """
        <h1>${escape(text(R.string.pairing_phone_opensubtitles_title))}</h1>
        <p class="sub">${escape(text(R.string.pairing_phone_opensubtitles_body))}</p>

        ${errorBox(error)}

        <form id="form" method="post" action="/signin">
          <input type="hidden" name="t" value="${escape(token)}">
          <label for="username">${escape(text(R.string.opensubtitles_username))}</label>
          <input id="username" name="username" type="text" required autocomplete="username"
                 autocapitalize="off" autocorrect="off" spellcheck="false">
          <label for="password">${escape(text(R.string.opensubtitles_password))}</label>
          <input id="password" name="password" type="password" required autocomplete="current-password">
          <button id="send" type="submit">${escape(text(R.string.opensubtitles_sign_in))}</button>
        </form>

        ${if (onboarding) skipButton(token, text, R.string.common_not_now) else ""}

        <p class="hint"><a href="$OPENSUBTITLES_SIGNUP" target="_blank" rel="noopener">${escape(
            text(R.string.opensubtitles_create_account),
        )}</a></p>
        <p class="hint">${escape(text(R.string.opensubtitles_attribution))}</p>

        <script>
          // The TV signs in with OpenSubtitles before it answers, so the button says it's working.
          document.getElementById('form').addEventListener('submit', function () {
            var send = document.getElementById('send');
            send.disabled = true;
            send.innerHTML = '<span class="spin" aria-hidden="true"></span>' + send.textContent;
          });
        </script>
        """.trimIndent(),
    )

    private fun errorBox(error: Error?): String = error?.let {
        "<div class=\"error\">${escape(it.sentence)}" +
            (if (it.detail.isNotBlank()) "<span class=\"detail\">${escape(it.detail)}</span>" else "") +
            "</div>"
    }.orEmpty()

    /** Onboarding's Skip for now, under Send to TV: the key is optional there, as on the TV. */
    private fun skipButton(token: String, text: (Int) -> String, label: Int = R.string.onboarding_tmdb_skip): String =
        """
        <form method="post" action="/skip">
          <input type="hidden" name="t" value="${escape(token)}">
          <button type="submit" class="secondary">${escape(text(label))}</button>
        </form>
        """.trimIndent()

    /** Why the TV turned a key down: the sentence and detail line `ErrorMessage` draws on the TV. */
    data class Error(val sentence: String, val detail: String)

    fun message(title: String, body: String): String = page(
        token = null,
        content = """
        <h1>${escape(title)}</h1>
        <p class="sub">${escape(body)}</p>
        """.trimIndent(),
    )

    /**
     * [token] lets the page fetch its fonts, which the TV serves only with it, as every route. A
     * message page has none and falls back to the phone's own fonts.
     */
    private fun page(token: String?, content: String): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
        <meta name="robots" content="noindex, nofollow">
        <title>Set up Settee</title>
        <style>
          ${if (token != null) fontFaces(token) else ""}
          :root { --bg:${SetteeColors.Background.hex}; --surface:${SetteeColors.Surface.hex};
                  --raised:${SetteeColors.Raised.hex}; --line:${SetteeColors.Rule.hex};
                  --text:${SetteeColors.Text.hex}; --muted:${SetteeColors.QuietText.hex};
                  --accent:${SetteeColors.Accent.hex}; --on-accent:${SetteeColors.OnAccent.hex}; }
          $STYLES
        </style>
        </head>
        <body><div class="wrap">
        <header class="brand">@@LOCKUP@@</header>
        $content
        <footer class="privacy">
          This page is served by your TV, over your own network. What you type goes straight to
          it and nowhere else. Settee has no accounts and no server of its own.
        </footer>
        </div></body>
        </html>
    """.trimIndent().replace("@@LOCKUP@@", PAIRING_LOCKUP.trim())

    /** Every page's layout; the colours are set on :root beside it, from Settee's own. */
    private const val STYLES = """
          * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
          body { margin:0; background:var(--bg); color:var(--text); padding:28px 20px 64px;
                 font:500 17px/1.5 "Alegreya Sans",-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }
          .wrap { max-width:460px; margin:0 auto; }
          h1 { font:400 1.9rem/1.2 "Young Serif",Georgia,serif; margin:0 0 6px; }
          .sub { color:var(--muted); margin:0 0 24px; }
          label { display:block; font-size:.95rem; font-weight:700; margin:18px 0 6px; }
          .opt { font-weight:400; color:var(--muted); font-size:.82rem; }
          input[type=text], input[type=url], input[type=password] {
            width:100%; padding:14px; font:inherit; font-size:17px; /* 16px+ stops iOS zooming on focus */
            background:var(--surface); color:var(--text);
            border:1px solid var(--line); border-radius:10px; }
          input:focus { outline:none; border-color:var(--accent); }
          .code { font-size:26px; letter-spacing:.34em; text-align:center; font-weight:700; }
          .hint { color:var(--muted); font-size:.85rem; margin:6px 0 0; }
          .seg { display:flex; gap:8px; margin-bottom:6px; }
          .segbtn { flex:1; margin:0; }
          .segbtn input { position:absolute; opacity:0; pointer-events:none; }
          .segbtn span { display:block; text-align:center; padding:12px;
            background:var(--surface); border:1px solid var(--line); border-radius:10px;
            font-weight:500; font-size:.95rem; }
          .segbtn input:checked + span { background:var(--raised); border-color:var(--accent); }
          button { width:100%; margin-top:26px; padding:17px; font:inherit; font-size:17px; font-weight:700;
            background:var(--accent); color:var(--on-accent); border:0; border-radius:999px; }
          button:active { opacity:.85; }
          button.secondary { margin-top:12px; background:transparent; color:var(--text);
            border:1px solid var(--line); font-weight:500; }
          button:disabled { opacity:.85; }
          .spin { display:inline-block; width:1em; height:1em; margin-right:.6em; vertical-align:-.15em;
            border:2px solid currentColor; border-right-color:transparent; border-radius:50%;
            animation:spin .8s linear infinite; }
          @keyframes spin { to { transform:rotate(360deg); } }
          @media (prefers-reduced-motion: reduce) { .spin { animation:none; } }
          hr { border:0; border-top:1px solid var(--line); margin:28px 0 4px; }
          .error { background:var(--surface); border:1px solid var(--accent); color:var(--text);
            padding:12px 14px; border-radius:10px; margin-bottom:18px; font-size:.92rem; }
          .error .detail { display:block; color:var(--muted); font-size:.8rem; margin-top:4px; }
          .steps { padding-left:1.3em; margin:28px 0 0; }
          .steps li { margin:0 0 10px; }
          a { color:var(--accent); }
          details { margin-top:28px; }
          summary { color:var(--accent); font-weight:700; cursor:pointer; }
          .brand { margin:0 0 28px; }
          .brand svg { display:block; height:40px; width:auto; }
          .privacy { color:var(--muted); font-size:.82rem; margin-top:36px; padding-top:18px;
            border-top:1px solid var(--line); }
    """

    /** Where a viewer signs up for TMDB, and where its account settings hand out the key. */
    private const val TMDB_SIGNUP = "https://www.themoviedb.org/signup"
    private const val TMDB_API_SETTINGS = "https://www.themoviedb.org/settings/api"
    private const val OPENSUBTITLES_SIGNUP = "https://www.opensubtitles.com/"

    /** Settee's fonts, fetched from the TV's own server. */
    private fun fontFaces(token: String): String {
        val t = escape(token)
        return """
          @font-face { font-family:"Young Serif"; font-weight:400; src:url('/fonts/young-serif.ttf?t=$t'); }
          @font-face { font-family:"Alegreya Sans"; font-weight:500; src:url('/fonts/alegreya-sans-medium.ttf?t=$t'); }
          @font-face { font-family:"Alegreya Sans"; font-weight:700; src:url('/fonts/alegreya-sans-bold.ttf?t=$t'); }
        """
    }

    /** As a CSS colour, so the page keeps step with the TV's own. */
    private val Color.hex: String
        get() = "#%06x".format(toArgb() and 0xFFFFFF)

    /** Minimal HTML escaping. Everything interpolated into the page goes through here. */
    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
