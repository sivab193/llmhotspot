package dev.llmhotspot.server

object LandingPage {
    val html = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>LLMHotspot</title><style>
        :root{color-scheme:dark;background:#0b1020;color:#eef2ff;font:16px system-ui}body{max-width:760px;margin:3rem auto;padding:1rem}
        main{background:#151b31;border:1px solid #2c365e;border-radius:20px;padding:1.5rem}textarea,input{box-sizing:border-box;width:100%;padding:.8rem;margin:.5rem 0;background:#0b1020;color:#fff;border:1px solid #46517a;border-radius:10px}button{background:#7c9cff;color:#081028;border:0;padding:.75rem 1rem;border-radius:10px;font-weight:700}pre{white-space:pre-wrap}</style></head>
        <body><main><h1>LLMHotspot</h1><p>A private model is running on this phone. Enter the session key shown in the Android app.</p>
        <input id="key" type="password" placeholder="Session API key"><textarea id="prompt" rows="5" placeholder="Message"></textarea><button id="send">Send</button><pre id="out"></pre>
        <script>send.onclick=async()=>{out.textContent='Thinking…';let r=await fetch('/v1/chat/completions',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+key.value},body:JSON.stringify({model:'current',messages:[{role:'user',content:prompt.value}]})});out.textContent=r.ok?(await r.json()).choices[0].message.content:await r.text()}</script></main></body></html>
    """.trimIndent()
}
