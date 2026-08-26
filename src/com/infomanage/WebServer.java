package com.infomanage;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 本地网页服务：提供实时信息流界面。
 * 仅提供固定路由，不提供任意文件读取，避免路径穿越等漏洞。
 */
public class WebServer {
    private static final Logger log = LoggerFactory.getLogger("InfoManage");
    private static final Gson GSON = new Gson();

    private final PluginConfig config;
    private final DataStore store;
    private HttpServer server;
    private final String html;

    public WebServer(PluginConfig config, DataStore store) {
        this.config = config;
        this.store = store;
        this.html = buildHtml();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.webHost, config.webPort), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/api/state", new StateHandler());
        server.createContext("/api/pin", new PinHandler());
        server.createContext("/api/player", new PlayerHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        log.info("InfoManage 网页服务已启动: http://{}:{}", config.webHost, config.webPort);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            send(ex, 200, "text/html; charset=utf-8", body);
        }
    }

    private final class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("title", config.title);
            state.put("onlineCount", store.getOnlineCount());
            state.put("totalPlayers", store.getTotalPlayers());
            state.put("players", store.getOnlinePlayers());
            state.put("feed", store.getFeed());
            state.put("pinned", store.getPinned());
            state.put("serverTime", System.currentTimeMillis());
            byte[] body = GSON.toJson(state).getBytes(StandardCharsets.UTF_8);
            send(ex, 200, "application/json; charset=utf-8", body);
        }
    }

    private final class PinHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "text/plain; charset=utf-8",
                        "method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Map<String, String> params = parseForm(ex);
            String name = params.get("name");
            boolean pin = !"false".equalsIgnoreCase(params.get("pin"));
            if (name == null || name.isBlank()) {
                send(ex, 400, "text/plain; charset=utf-8",
                        "name required".getBytes(StandardCharsets.UTF_8));
                return;
            }
            boolean pinned = store.setPin(name, pin);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("name", name.trim());
            resp.put("pinned", pinned);
            byte[] body = GSON.toJson(resp).getBytes(StandardCharsets.UTF_8);
            send(ex, 200, "application/json; charset=utf-8", body);
        }
    }

    private final class PlayerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String name = null;
            String query = ex.getRequestURI().getQuery();
            if (query != null) {
                for (String kv : query.split("&")) {
                    int i = kv.indexOf('=');
                    if (i < 0) continue;
                    String k = URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8);
                    if ("name".equals(k)) {
                        name = URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
                    }
                }
            }
            Map<String, Object> detail = store.getPlayerDetail(name);
            if (detail == null) {
                send(ex, 200, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            detail.put("ok", true);
            byte[] body = GSON.toJson(detail).getBytes(StandardCharsets.UTF_8);
            send(ex, 200, "application/json; charset=utf-8", body);
        }
    }

    private Map<String, String> parseForm(HttpExchange ex) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        byte[] raw = ex.getRequestBody().readAllBytes();
        String body = new String(raw, StandardCharsets.UTF_8);
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            String k = URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private String buildHtml() {
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>服务器状态</title>
<style>
  :root { --bg:#0a0a0c; --panel:#141419; --text:#e8e8ec; --muted:#8a8a96; --accent:#4aa3ff; --good:#3ecf8e; --warn:#f5a623; }
  * { box-sizing:border-box; margin:0; padding:0; }
  html,body { height:100%; }
  body { background:var(--bg); color:var(--text); font-family:-apple-system,"Segoe UI","Microsoft YaHei",Roboto,sans-serif; display:flex; flex-direction:column; overflow:hidden; }
  header { display:flex; align-items:center; gap:16px; padding:14px 22px; border-bottom:1px solid #1e1e26; flex:0 0 auto; }
  header .logo { font-size:20px; font-weight:800; letter-spacing:1px; }
  header .stats { display:flex; gap:20px; margin-left:auto; font-size:13px; color:var(--muted); }
  header .stats b { color:var(--text); font-size:17px; margin-left:4px; }
  main { flex:1 1 auto; display:flex; min-height:0; }
  .left { width:330px; min-width:280px; border-right:1px solid #1e1e26; display:flex; flex-direction:column; }
  .left h3 { padding:12px 16px 6px; font-size:12px; color:var(--muted); font-weight:600; text-transform:uppercase; letter-spacing:1px; }
  .players { flex:1; overflow-y:auto; padding:4px 12px 12px; }
  .player { display:flex; align-items:center; gap:10px; padding:8px 10px; border-radius:8px; margin-bottom:5px; background:var(--panel); }
  .player.pinned { border:1px solid var(--accent); }
  .avatar { width:30px; height:30px; border-radius:6px; display:inline-flex; align-items:center; justify-content:center; font-weight:700; font-size:15px; color:#fff; flex:0 0 auto; }
  .player { cursor:pointer; }
  .player .name { flex:1; font-size:14px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .badge { font-size:10px; padding:2px 6px; border-radius:4px; font-weight:700; }
  .badge.premium { background:rgba(62,207,142,.15); color:var(--good); }
  .badge.offline { background:rgba(245,166,35,.15); color:var(--warn); }
  .pin { cursor:pointer; font-size:15px; opacity:.45; user-select:none; padding:0 2px; }
  .pin.on { opacity:1; }
  .feed { flex:1; overflow-y:auto; padding:14px 20px; font-size:14px; line-height:1.7; }
  .entry { padding:2px 0; border-bottom:1px dashed #191920; word-break:break-word; }
  .entry .t { color:var(--muted); font-size:11px; margin-right:7px; font-variant-numeric:tabular-nums; }
  .entry.chat .who { color:var(--accent); font-weight:600; }
  .entry.system { color:#c9c9d2; }
  .entry.join { color:var(--good); }
  .entry.leave { color:#e06060; }
  .empty { color:var(--muted); font-style:italic; padding:20px 4px; }
  .modal { position:fixed; inset:0; background:rgba(0,0,0,.55); display:none; align-items:center; justify-content:center; z-index:10; }
  .modal-box { background:var(--panel); border:1px solid #26262e; border-radius:12px; width:420px; max-width:92vw; max-height:82vh; overflow:auto; box-shadow:0 10px 40px rgba(0,0,0,.5); }
  .modal-head { display:flex; align-items:center; justify-content:space-between; padding:14px 18px; border-bottom:1px solid #26262e; }
  .modal-head .title { font-size:16px; font-weight:700; }
  .close { cursor:pointer; font-size:22px; color:var(--muted); line-height:1; }
  .close:hover { color:var(--text); }
  .modal-body { padding:10px 18px 16px; }
  .drow { display:flex; justify-content:space-between; gap:16px; padding:8px 0; border-bottom:1px dashed #1f1f26; font-size:14px; }
  .drow .dk { color:var(--muted); flex:0 0 auto; }
  .drow .dv { color:var(--text); text-align:right; word-break:break-word; }
</style>
</head>
<body>
<header>
  <div class="logo" id="title">服务器状态</div>
  <div class="stats">
    <span>在线<b id="onlineCount">0</b></span>
  </div>
</header>
<main>
  <div class="left">
    <h3>在线玩家</h3>
    <div class="players" id="players"></div>
  </div>
  <div class="feed" id="feed"></div>
</main>
<div class="modal" id="detailModal">
  <div class="modal-box">
    <div class="modal-head"><span class="title" id="detailName"></span><span class="close" onclick="closeDetail()">×</span></div>
    <div class="modal-body" id="detailBody"></div>
  </div>
</div>
<script>
const POLL = 2000;
const seen = new Set();
const feedEl = document.getElementById('feed');
const playersEl = document.getElementById('players');
const playerRows = new Map();

function avatarColor(name){
  const clean = name.replace(/^[|]+/, '');
  let h = 0;
  for (let i = 0; i < clean.length; i++) h = (h * 31 + clean.charCodeAt(i)) & 0xffff;
  return 'hsl(' + (h % 360) + ', 55%, 45%)';
}

function makeAvatar(p){
  const d = document.createElement('div');
  d.className = 'avatar';
  const skin = (p && p.skin) ? p.skin : '';
  if (skin){
    d.style.backgroundImage = 'url("' + skin + '"), url("' + skin + '")';
    d.style.backgroundSize = '256px 256px, 256px 256px';
    d.style.backgroundPosition = '-32px -32px, -160px -32px';
    d.style.backgroundRepeat = 'no-repeat';
    d.style.imageRendering = 'pixelated';
  } else {
    const name = (p && p.name) || '';
    d.style.background = avatarColor(name);
    d.textContent = (name.replace(/^[|]+/, '') || '?').charAt(0).toUpperCase();
  }
  return d;
}

function buildPlayerRow(p){
  const row = document.createElement('div');
  row.className = 'player' + (p.pinned ? ' pinned' : '');
  row.appendChild(makeAvatar(p));
  const nm = document.createElement('div');
  nm.className = 'name';
  nm.textContent = p.name;
  nm.title = p.name;
  row.appendChild(nm);
  const badge = document.createElement('span');
  badge.className = 'badge ' + (p.premium ? 'premium' : 'offline');
  badge.textContent = p.premium ? '正版' : '离线';
  row.appendChild(badge);
  const pin = document.createElement('span');
  pin.className = 'pin' + (p.pinned ? ' on' : '');
  pin.textContent = p.pinned ? '📌' : '📍';
  pin.title = p.pinned ? '取消置顶' : '置顶';
  pin.addEventListener('click', function(e){ e.stopPropagation(); togglePin(p.name); });
  row.appendChild(pin);
  row.addEventListener('click', function(){ showPlayerDetail(p.name); });
  row._data = { badge:badge, pin:pin, nm:nm };
  return row;
}

function updatePlayerRow(row, p){
  const d = row._data;
  d.badge.textContent = p.premium ? '正版' : '离线';
  d.badge.className = 'badge ' + (p.premium ? 'premium' : 'offline');
  d.pin.textContent = p.pinned ? '📌' : '📍';
  d.pin.className = 'pin' + (p.pinned ? ' on' : '');
  d.pin.title = p.pinned ? '取消置顶' : '置顶';
  row.className = 'player' + (p.pinned ? ' pinned' : '');
}

function renderPlayers(list){
  const entries = list || [];
  const wanted = new Set(entries.map(p => p.name));
  for (const [name, row] of [...playerRows]){
    if (!wanted.has(name)){ row.remove(); playerRows.delete(name); }
  }
  let prev = null;
  for (const p of entries){
    let row = playerRows.get(p.name);
    if (!row){ row = buildPlayerRow(p); playerRows.set(p.name, row); }
    else updatePlayerRow(row, p);
    if (prev === null){
      if (playersEl.firstChild !== row) playersEl.insertBefore(row, playersEl.firstChild);
    } else if (prev.nextSibling !== row){
      prev.after(row);
    }
    prev = row;
  }
  const empty = playersEl.querySelector('.empty');
  if (entries.length === 0){
    if (!empty){ const d = document.createElement('div'); d.className = 'empty'; d.textContent = '暂无玩家在线'; playersEl.appendChild(d); }
  } else if (empty){ empty.remove(); }
}

const MC_COLORS = {
  '0':'#000000','1':'#0000AA','2':'#00AA00','3':'#00AAAA','4':'#AA0000',
  '5':'#AA00AA','6':'#FFAA00','7':'#AAAAAA','8':'#555555','9':'#5555FF',
  'a':'#55FF55','b':'#55FFFF','c':'#FF5555','d':'#FF55FF','e':'#FFFF55','f':'#FFFFFF'
};

function renderMcText(text){
  const frag = document.createDocumentFragment();
  let color = null, bold = false, italic = false, underline = false, strike = false;
  let buf = '';
  function flush(){
    if (!buf.length) return;
    const s = document.createElement('span');
    s.textContent = buf;
    if (color) s.style.color = color;
    if (bold) s.style.fontWeight = 'bold';
    if (italic) s.style.fontStyle = 'italic';
    if (underline) s.style.textDecoration = 'underline';
    if (strike) s.style.textDecoration = 'line-through';
    frag.appendChild(s);
    buf = '';
  }
  for (let i = 0; i < text.length; i++){
    const ch = text[i];
    if (ch === '\u00a7' && i + 1 < text.length){
      const code = text[i + 1].toLowerCase();
      if (MC_COLORS[code]){ flush(); color = MC_COLORS[code]; bold = italic = underline = strike = false; }
      else if (code === 'l'){ flush(); bold = true; }
      else if (code === 'o'){ flush(); italic = true; }
      else if (code === 'n'){ flush(); underline = true; }
      else if (code === 'm'){ flush(); strike = true; }
      else if (code === 'r'){ flush(); color = null; bold = italic = underline = strike = false; }
      i++;
      continue;
    }
    buf += ch;
  }
  flush();
  return frag;
}

function fmtTime(ts){
  const d = new Date(ts);
  const p = n => String(n).padStart(2, '0');
  return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
}

function fmtDate(ts){
  if (!ts) return '—';
  const d = new Date(ts);
  const p = n => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth()+1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

function fmtDuration(ms){
  if (!ms || ms <= 0) return '0秒';
  const s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (d > 0) return d + '天' + h + '小时' + m + '分';
  if (h > 0) return h + '小时' + m + '分';
  if (m > 0) return m + '分' + sec + '秒';
  return sec + '秒';
}

function renderFeed(feed){
  let appended = false;
  for (const e of feed){
    const key = e.ts + '|' + e.type + '|' + e.name + '|' + e.message;
    if (seen.has(key)) continue;
    seen.add(key);
    const div = document.createElement('div');
    div.className = 'entry ' + e.type;
    const t = document.createElement('span');
    t.className = 't';
    t.textContent = fmtTime(e.ts);
    div.appendChild(t);
    if (e.type === 'chat'){
      const who = document.createElement('span');
      who.className = 'who';
      who.textContent = '<' + e.name + '>';
      div.appendChild(who);
      div.appendChild(document.createTextNode(' '));
      div.appendChild(renderMcText(e.message));
    } else if (e.type === 'system'){
      div.appendChild(renderMcText(e.message));
    } else {
      div.appendChild(document.createTextNode(e.message));
    }
    feedEl.appendChild(div);
    appended = true;
  }
  if (appended) feedEl.scrollTop = feedEl.scrollHeight;
  while (feedEl.childElementCount > 800) feedEl.removeChild(feedEl.firstChild);
}

function showPlayerDetail(name){
  fetch('/api/player?name=' + encodeURIComponent(name), { cache:'no-store' })
    .then(r => r.json())
    .then(d => {
      if (!d || d.ok === false){ alert('未找到该玩家的记录'); return; }
      renderDetail(d);
    })
    .catch(() => {});
}

function addRow(body, k, v){
  const row = document.createElement('div');
  row.className = 'drow';
  const dk = document.createElement('span');
  dk.className = 'dk';
  dk.textContent = k;
  const dv = document.createElement('span');
  dv.className = 'dv';
  dv.textContent = v;
  row.appendChild(dk);
  row.appendChild(dv);
  body.appendChild(row);
}

function renderDetail(d){
  document.getElementById('detailName').textContent = d.name;
  const body = document.getElementById('detailBody');
  body.innerHTML = '';
  const status = d.online ? ('在线（当前已在线 ' + fmtDuration(d.onlineSeconds * 1000) + '）') : '离线';
  const lastLogout = d.online ? '在线' : fmtDate(d.lastLogoutMs);
  addRow(body, '状态', status);
  addRow(body, '验证', d.premium ? '正版' : '离线');
  addRow(body, '累计在线时长', fmtDuration(d.totalOnlineMs));
  addRow(body, '首次加入时间', fmtDate(d.firstSeenMs));
  addRow(body, '上次登录时间', fmtDate(d.lastLoginMs));
  addRow(body, '上次退出时间', lastLogout);
  addRow(body, '加入次数', String(d.sessions || 0));
  addRow(body, '死亡次数', String(d.deathCount || 0));
  addRow(body, '发言次数', String(d.messageCount || 0));
  addRow(body, '击杀数量', String(d.killCount || 0));
  document.getElementById('detailModal').style.display = 'flex';
}

function closeDetail(){
  document.getElementById('detailModal').style.display = 'none';
}

async function togglePin(name){
  try {
    await fetch('/api/pin', {
      method:'POST',
      headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body:'name=' + encodeURIComponent(name)
    });
  } catch(e){}
  refresh();
}

async function refresh(){
  try {
    const r = await fetch('/api/state', { cache:'no-store' });
    const s = await r.json();
    document.getElementById('title').textContent = s.title || '服务器状态';
    document.getElementById('onlineCount').textContent = String(s.onlineCount || 0);
    document.getElementById('totalPlayers').textContent = String(s.totalPlayers || 0);
    renderPlayers(s.players || []);
    renderFeed(s.feed || []);
  } catch(e){}
}

document.getElementById('detailModal').addEventListener('click', function(e){
  if (e.target === this) closeDetail();
});

refresh();
setInterval(refresh, POLL);
</script>
</body>
</html>
""";
    }
}
