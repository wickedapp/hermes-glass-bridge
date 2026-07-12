<script def>
{
  "navigationBarTitleText": "Rokid Dev Console",
  "description": "Rokid Dev Console: press Enter to start Rokid AIUI SpeechRecognition; final transcript is sent to Hermes bridge over WebSocket.",
  "schema": {
    "data": {
      "type": "object",
      "properties": {}
    }
  }
}
</script>

<script setup>
let recognition = null;
let restartTimer = null;
let watchdogTimer = null;
let startSeq = 0;
let bridge = null;
let bridgeReconnectTimer = null;
let bridgeReady = false;
let hermesAckTimer = null;
let lastSentFinal = '';
let lastSentAt = 0;

const WATCHDOG_MS = 7000;
const HERMES_ACK_TIMEOUT_MS = 10000;
const APP_VERSION = 'v-aiui-clean-font12-2029';
const BRIDGE_URL = 'ws://127.0.0.1:8765/ws/glass';
const PROJECT = 'rokid-glasses';

function safeText(value) {
  if (value === undefined || value === null) return '';
  return String(value);
}

function readBestTranscript(event) {
  try {
    const best = event && event.results && event.results[0] && event.results[0][0];
    if (!best) return { transcript: '', confidence: '' };
    return {
      transcript: safeText(best.transcript),
      confidence: best.confidence === undefined ? '' : String(best.confidence)
    };
  } catch (err) {
    return { transcript: '', confidence: '' };
  }
}

export default {
  data: {
    status: 'ready: press Enter to speak',
    transcript: '',
    partial: '',
    confidence: '',
    events: ['press Enter to start 7s ASR'],
    supported: 'checking...',
    isListening: false,
    mode: 'Hermes',
    bridgeStatus: 'bridge: connecting...',
    prompt: '',
    agent: '',
    agentStatus: 'Hermes idle',
    sessions: [],
    sessionText: '',
    selectedSessionId: '',
    consoleTitle: 'Rokid Dev Console',
    appVersion: APP_VERSION
  },

  onLoad() {
    const hasGlobal = typeof SpeechRecognition !== 'undefined';
    const hasWx = typeof wx !== 'undefined' && !!wx.getSpeechRecognizer;
    this.setData({
      supported: `SpeechRecognition=${hasGlobal ? 'yes' : 'no'} wx.getSpeechRecognizer=${hasWx ? 'yes' : 'no'}`,
      status: 'ready: press Enter to speak'
    });
    this.addEvent('onLoad ' + APP_VERSION + ' bridge+ASR ' + this.data.supported);
    this.connectBridge();
  },

  onShow() {
    this.addEvent('onShow ' + APP_VERSION + ' -> ensure bridge only');
    this.connectBridge();
  },

  onHide() {
    this.addEvent('onHide');
    this.stopListening('hide');
  },

  onUnload() {
    this.stopListening('unload');
  },

  stopListening(reason = 'stop') {
    if (restartTimer) {
      clearTimeout(restartTimer);
      restartTimer = null;
    }
    if (watchdogTimer) {
      clearTimeout(watchdogTimer);
      watchdogTimer = null;
    }
    try {
      if (recognition) recognition.abort();
    } catch (err) {}
    recognition = null;
    this.setData({ status: 'ready: press Enter to speak', isListening: false });
    this.addEvent('stopListening ' + reason);
  },

  addEvent(message) {
    const now = new Date().toLocaleTimeString();
    const events = [`${now} ${message}`].concat(this.data.events || []).slice(0, 10);
    this.setData({ events });
    console.log('[hermes-glass]', message);
    try {
      if (bridge && bridge.readyState === 1) {
        bridge.send(JSON.stringify({ type: 'client_log', version: APP_VERSION, text: message, level: 'info' }));
      }
    } catch (err) {}
  },

  connectBridge() {
    try {
      if (bridge && (bridge.readyState === 0 || bridge.readyState === 1)) return;
      if (bridgeReconnectTimer) {
        clearTimeout(bridgeReconnectTimer);
        bridgeReconnectTimer = null;
      }
      bridgeReady = false;
      this.setData({ bridgeStatus: 'bridge: connecting...' });
      bridge = new WebSocket(BRIDGE_URL);
      bridge.onopen = () => {
        bridgeReady = true;
        this.setData({ bridgeStatus: 'bridge: connected', agentStatus: 'Hermes ready' });
        this.addEvent('bridge connected');
        try { bridge.send(JSON.stringify({ type: 'ping' })); } catch (err) {}
      };
      bridge.onmessage = (event) => {
        let msg = null;
        try { msg = JSON.parse(event.data); } catch (err) { msg = { type: 'text', text: safeText(event.data) }; }
        this.handleBridgeMessage(msg);
      };
      bridge.onerror = () => {
        bridgeReady = false;
        this.setData({ bridgeStatus: 'bridge: error' });
        this.addEvent('bridge error');
      };
      bridge.onclose = () => {
        bridgeReady = false;
        this.setData({ bridgeStatus: 'bridge: closed; retrying' });
        this.addEvent('bridge closed');
        bridgeReconnectTimer = setTimeout(() => {
          bridgeReconnectTimer = null;
          this.connectBridge();
        }, 1500);
      };
    } catch (err) {
      bridgeReady = false;
      this.setData({ bridgeStatus: 'bridge exception: ' + safeText(err && err.message || err) });
      this.addEvent('bridge exception ' + safeText(err && err.message || err));
    }
  },

  handleBridgeMessage(msg) {
    const type = msg && msg.type;
    if (type && type.indexOf('agent_') === 0 && hermesAckTimer) {
      clearTimeout(hermesAckTimer);
      hermesAckTimer = null;
    }
    if (type === 'hello') {
      this.setData({ bridgeStatus: 'bridge: hello ' + safeText(msg.client_id) });
      return;
    }
    if (type === 'pong') {
      this.setData({ bridgeStatus: 'bridge: connected' });
      return;
    }
    if (type === 'agent_start' || type === 'agent_status') {
      this.setData({ agentStatus: safeText(msg.text || 'Hermes thinking...'), agent: '' });
      return;
    }
    if (type === 'agent_delta') {
      const text = (this.data.agent || '') + safeText(msg.text);
      this.setData({ agent: text.slice(-900), agentStatus: 'Hermes responding...' });
      return;
    }
    if (type === 'agent_done') {
      const text = safeText(msg.text || this.data.agent || '');
      this.setData({ agent: text.slice(-900), agentStatus: 'Hermes done' });
      this.addEvent('Hermes done');
      return;
    }
    if (type === 'agent_error' || type === 'error') {
      this.setData({ agentStatus: 'Hermes error', agent: safeText(msg.text || msg.error || msg.detail || 'unknown error').slice(-900) });
      this.addEvent('Hermes error');
      return;
    }
    if (type === 'session_list') {
      const sessions = msg.sessions || [];
      const lines = sessions.slice(0, 8).map((s, idx) => {
        return `${idx + 1}. ${safeText(s.kind)} ${safeText(s.status)} ${safeText(s.id)}`;
      });
      this.setData({
        mode: 'Sessions',
        sessions,
        sessionText: lines.length ? lines.join('\n') : 'No tmux sessions found',
        agentStatus: `sessions: ${safeText(msg.count || sessions.length)}`
      });
      this.addEvent('session list received');
      return;
    }
    if (type === 'session_attached' || type === 'session_output' || type === 'session_started') {
      const sid = safeText(msg.session_id || msg.id || '');
      this.setData({
        mode: 'Sessions',
        selectedSessionId: sid,
        sessionText: safeText(msg.summary || msg.output || '').slice(-1000),
        agentStatus: type + ' ' + sid
      });
      this.addEvent(type);
      return;
    }
    if (type === 'session_error') {
      this.setData({ mode: 'Sessions', agentStatus: 'session error', sessionText: safeText(msg.text || 'unknown session error').slice(-1000) });
      this.addEvent('session error');
      return;
    }
  },

  sendToBridge(payload, notReadyStatus = 'bridge not ready') {
    this.connectBridge();
    const raw = JSON.stringify(payload);
    try {
      if (bridge && bridge.readyState === 1 && bridgeReady) {
        bridge.send(raw);
      } else {
        this.setData({ agentStatus: notReadyStatus });
        setTimeout(() => {
          try {
            if (bridge && bridge.readyState === 1) bridge.send(raw);
            else this.setData({ agentStatus: 'bridge still not ready' });
          } catch (err) {
            this.setData({ agentStatus: 'send retry failed', agent: safeText(err && err.message || err) });
          }
        }, 1200);
      }
    } catch (err) {
      this.setData({ agentStatus: 'send failed', agent: safeText(err && err.message || err) });
      this.addEvent('send failed');
    }
  },

  requestSessionList() {
    this.setData({ mode: 'Sessions', agentStatus: 'loading sessions...', sessionText: '' });
    this.addEvent('request session list');
    this.sendToBridge({ type: 'session_list' }, 'bridge not ready for sessions');
  },

  attachSessionByIndex(index) {
    const sessions = this.data.sessions || [];
    const session = sessions[index - 1];
    if (!session || !session.id) {
      this.setData({ mode: 'Sessions', agentStatus: 'no session #' + index });
      return;
    }
    this.setData({ mode: 'Sessions', selectedSessionId: session.id, agentStatus: 'attaching ' + session.id });
    this.sendToBridge({ type: 'session_attach', session_id: session.id, lines: 80 }, 'bridge not ready for attach');
  },

  routeFinalTranscript(text) {
    const prompt = safeText(text).trim();
    if (!prompt) return;
    const intent = this.classifyIntent(prompt);
    this.addEvent('AIUI ' + intent.intent);
    this.setData({ agentStatus: 'AIUI: ' + intent.intent });
    this.executeIntent(intent);
  },

  classifyIntent(text) {
    const raw = safeText(text).trim();
    const lower = raw.toLowerCase();
    const normalized = lower.replace(/[，。！？,.!?]/g, ' ').replace(/\s+/g, ' ').trim();

    if (!raw) return { intent: 'unknown', text: raw };
    if (normalized === '停止' || normalized === '取消' || normalized.indexOf('不要送') >= 0 || normalized.indexOf('cancel') >= 0 || normalized.indexOf('stop') >= 0) {
      return { intent: 'cancel', text: raw };
    }
    if (normalized.indexOf('清空') >= 0 || normalized.indexOf('clear screen') >= 0 || normalized.indexOf('clear console') >= 0) {
      return { intent: 'clear', text: raw };
    }
    if (normalized.indexOf('重連') >= 0 || normalized.indexOf('重新連') >= 0 || normalized.indexOf('reconnect') >= 0) {
      return { intent: 'reconnect', text: raw };
    }
    if (normalized.indexOf('hermes mode') >= 0 || normalized.indexOf('切到 hermes') >= 0 || normalized.indexOf('切換到 hermes') >= 0) {
      return { intent: 'mode_switch', mode: 'Hermes', text: raw };
    }
    if (normalized.indexOf('session mode') >= 0 || normalized.indexOf('sessions mode') >= 0 || normalized.indexOf('切到 session') >= 0 || normalized.indexOf('切換到 session') >= 0) {
      return { intent: 'mode_switch', mode: 'Sessions', text: raw };
    }
    if (normalized.indexOf('列出') >= 0 && (normalized.indexOf('session') >= 0 || normalized.indexOf('sessions') >= 0 || normalized.indexOf('會話') >= 0)) {
      return { intent: 'session_list', text: raw };
    }
    if (normalized.indexOf('list sessions') >= 0 || normalized.indexOf('show sessions') >= 0) {
      return { intent: 'session_list', text: raw };
    }
    if (normalized.indexOf('第一') >= 0 || normalized.indexOf('第一個') >= 0 || normalized.indexOf('open 1') >= 0 || normalized.indexOf('attach 1') >= 0) {
      if (normalized.indexOf('打開') >= 0 || normalized.indexOf('開啟') >= 0 || normalized.indexOf('attach') >= 0 || normalized.indexOf('open') >= 0) {
        return { intent: 'session_attach', index: 1, text: raw };
      }
    }
    const sendMarkers = ['發送', '送到', 'send'];
    const sessionMarkers = ['目前 session', '現在 session', 'current session', 'selected session'];
    const hasSend = sendMarkers.some((m) => normalized.indexOf(m) >= 0);
    const hasSession = sessionMarkers.some((m) => normalized.indexOf(m) >= 0);
    if (hasSend && hasSession) {
      let body = raw.replace(/^.*?(發送|送到|send)\s*/i, '').replace(/(到|to)?\s*(目前|現在|current|selected)?\s*session\s*/ig, '').trim();
      if (!body) body = raw;
      return { intent: 'session_send', text: raw, body };
    }
    if (normalized.indexOf('問 hermes') >= 0 || normalized.indexOf('ask hermes') >= 0) {
      const body = raw.replace(/^.*?(問\s*Hermes|問hermes|ask\s+hermes)\s*/i, '').trim() || raw;
      return { intent: 'hermes_prompt', text: raw, prompt: body };
    }

    // Default: normal conversation goes to Hermes, but now it is explicit in AIUI logs.
    return { intent: 'hermes_prompt', text: raw, prompt: raw };
  },

  executeIntent(intent) {
    const kind = intent && intent.intent;
    if (kind === 'cancel') {
      this.stopListening('aiui-cancel');
      this.setData({ agentStatus: 'AIUI: canceled' });
      return;
    }
    if (kind === 'clear') {
      this.setData({ prompt: '', agent: '', partial: '', transcript: '', agentStatus: 'AIUI: cleared', events: ['cleared ' + APP_VERSION] });
      return;
    }
    if (kind === 'reconnect') {
      try { if (bridge) bridge.close(); } catch (err) {}
      bridge = null;
      bridgeReady = false;
      this.connectBridge();
      this.setData({ agentStatus: 'AIUI: reconnecting bridge' });
      return;
    }
    if (kind === 'mode_switch') {
      this.setData({ mode: intent.mode || 'Hermes', agentStatus: 'AIUI: mode ' + safeText(intent.mode || 'Hermes') });
      return;
    }
    if (kind === 'session_list') {
      this.requestSessionList();
      return;
    }
    if (kind === 'session_attach') {
      this.attachSessionByIndex(intent.index || 1);
      return;
    }
    if (kind === 'session_send') {
      if (!this.data.selectedSessionId) {
        this.setData({ mode: 'Sessions', agentStatus: 'AIUI: no selected session', sessionText: '先說：列出 sessions，然後 打開第一個' });
        return;
      }
      this.setData({ mode: 'Sessions', agentStatus: 'AIUI: send to ' + this.data.selectedSessionId });
      this.sendToBridge({ type: 'session_send', session_id: this.data.selectedSessionId, text: intent.body || intent.text || '', lines: 80 }, 'bridge not ready for session send');
      return;
    }
    if (kind === 'hermes_prompt') {
      this.sendToHermes(intent.prompt || intent.text || '');
      return;
    }
    this.setData({ agentStatus: 'AIUI: unclear', agent: safeText(intent && intent.text || '').slice(-500) });
  },

  sendToHermes(text) {
    const prompt = safeText(text).trim();
    if (!prompt) return;
    const now = Date.now();
    if (prompt === lastSentFinal && now - lastSentAt < 5000) {
      this.addEvent('skip duplicate final');
      return;
    }
    lastSentFinal = prompt;
    lastSentAt = now;
    this.setData({ prompt, agent: '', agentStatus: 'sending to Hermes...' });
    this.addEvent('send final -> Hermes');
    this.connectBridge();
    const payload = JSON.stringify({ type: 'agent_prompt', text: prompt, project: PROJECT });
    if (hermesAckTimer) clearTimeout(hermesAckTimer);
    hermesAckTimer = setTimeout(() => {
      hermesAckTimer = null;
      this.setData({ agentStatus: 'Hermes timeout; reconnecting', agent: 'No agent response after send. Bridge will reconnect.' });
      this.addEvent('Hermes ack timeout');
      try { if (bridge) bridge.close(); } catch (err) {}
      bridge = null;
      bridgeReady = false;
      this.connectBridge();
    }, HERMES_ACK_TIMEOUT_MS);
    try {
      if (bridge && bridge.readyState === 1 && bridgeReady) {
        bridge.send(payload);
      } else {
        this.setData({ agentStatus: 'bridge not ready; queued retry' });
        setTimeout(() => {
          try {
            if (bridge && bridge.readyState === 1) bridge.send(payload);
            else this.setData({ agentStatus: 'bridge still not ready' });
          } catch (err) {
            this.setData({ agentStatus: 'send retry failed', agent: safeText(err && err.message || err) });
          }
        }, 1200);
      }
    } catch (err) {
      this.setData({ agentStatus: 'send failed', agent: safeText(err && err.message || err) });
      this.addEvent('send failed');
    }
  },

  handleTap() {
    this.addEvent('tap ignored; press Enter');
  },

  onKeyUp(event) {
    const code = safeText(event && (event.key || event.code || event.keyCode || event.which));
    this.addEvent('key ' + code);
    if (code === 'Enter' || code === 'NumpadEnter' || code === '13' || code === '66') {
      this.startAsr('key-' + code);
    }
  },

  armWatchdog(seq) {
    if (watchdogTimer) clearTimeout(watchdogTimer);
    watchdogTimer = setTimeout(() => {
      watchdogTimer = null;
      if (seq !== startSeq) return;
      // User-requested behavior: one Enter press opens one 7s ASR window.
      // If no final speech arrives, abort and stay idle. No auto-restart.
      this.setData({ status: 'listen timeout; press Enter', isListening: false });
      try {
        if (recognition) recognition.abort();
      } catch (err) {}
      recognition = null;
      this.addEvent('listen timeout #' + seq);
    }, WATCHDOG_MS);
  },

  startAsr(reason = 'manual') {
    const seq = startSeq + 1;
    if (restartTimer) {
      clearTimeout(restartTimer);
      restartTimer = null;
    }
    if (recognition && this.data && this.data.isListening) {
      this.addEvent(`skip startAsr ${reason}: already listening #${startSeq}`);
      return;
    }
    startSeq = seq;
    this.addEvent(`startAsr ${reason} #${seq}`);
    if (typeof SpeechRecognition === 'undefined') {
      this.setData({ status: 'error: SpeechRecognition undefined' });
      this.addEvent('SpeechRecognition undefined');
      return;
    }

    try {
      if (watchdogTimer) {
        clearTimeout(watchdogTimer);
        watchdogTimer = null;
      }
      recognition = new SpeechRecognition();
      recognition.lang = 'zh-CN';
      recognition.continuous = false;
      recognition.interimResults = true;
      recognition.maxAlternatives = 3;

      recognition.onstart = () => {
        if (seq !== startSeq) return;
        this.setData({ status: `listening #${seq}`, isListening: true });
        this.addEvent('onstart #' + seq);
        this.armWatchdog(seq);
      };
      recognition.onaudiostart = () => this.addEvent('onaudiostart #' + seq);
      recognition.onspeechstart = () => this.addEvent('onspeechstart #' + seq);
      recognition.onspeechend = () => this.addEvent('onspeechend #' + seq);
      recognition.onaudioend = () => this.addEvent('onaudioend #' + seq);
      recognition.onnomatch = () => {
        if (seq !== startSeq) return;
        this.setData({ status: 'no match; ready', isListening: false });
        this.addEvent('onnomatch #' + seq);
      };
      recognition.onerror = (event) => {
        if (seq !== startSeq) return;
        const err = safeText(event && event.error);
        const msg = safeText(event && event.message);
        recognition = null;
        if (err === 'aborted' || err === 'no-speech') {
          this.setData({ status: 'ready: press Enter to speak', isListening: false });
          this.addEvent(`quiet ${err} #${seq}`);
          return;
        }
        this.addEvent(`onerror #${seq} ${err}`);
        this.setData({ status: `mic error: ${err} ${msg}; ready`, isListening: false });
      };
      recognition.onend = () => {
        if (seq !== startSeq) return;
        this.setData({ status: 'ready: press Enter to speak', isListening: false });
        recognition = null;
        this.addEvent('onend #' + seq);
      };
      recognition.onresult = (event) => {
        if (seq !== startSeq) return;
        if (watchdogTimer) {
          clearTimeout(watchdogTimer);
          watchdogTimer = null;
        }
        const best = readBestTranscript(event);
        let finalFlag = false;
        try { finalFlag = !!(event && event.results && event.results[0] && event.results[0].isFinal); } catch (err) {}
        if (finalFlag) {
          this.setData({ transcript: best.transcript, partial: '', confidence: best.confidence, status: 'final result; sending to Hermes' });
          this.addEvent(`final #${seq}: ${best.transcript}`);
          this.routeFinalTranscript(best.transcript);
          recognition = null;
          return;
        } else {
          this.setData({ partial: best.transcript, confidence: best.confidence, status: 'partial result; still listening' });
          this.addEvent(`partial #${seq}: ${best.transcript}`);
        }
        this.armWatchdog(seq);
      };

      recognition.start();
      this.setData({ status: `start() called #${seq}` });
    } catch (err) {
      this.setData({ status: 'exception: ' + safeText(err && err.message || err), isListening: false });
      this.addEvent('exception ' + safeText(err && err.message || err));
    }
  }
};
</script>

<page>
  <view class="aiui" bindtap="handleTap" bindkeyup="onKeyUp" bindkeydown="onKeyUp">
    <view class="topbar">
      <text class="brand">{{ consoleTitle }}</text>
      <text class="version">{{ appVersion }}</text>
    </view>

    <view class="statusCard">
      <text class="mode">{{ mode }}</text>
      <text class="state">{{ status }}</text>
      <text class="hint">Press Enter · speak within 7s</text>
    </view>

    <view class="card">
      <text class="label">You said</text>
      <text class="primary">{{ transcript }}</text>
      <text class="secondary">{{ partial }}</text>
    </view>

    <view class="card grow">
      <text class="label">Hermes</text>
      <text class="statusText">{{ agentStatus }}</text>
      <text class="answer">{{ agent }}</text>
    </view>

    <view class="footer">
      <text class="footerText">{{ bridgeStatus }}</text>
      <text class="footerText">{{ selectedSessionId }}</text>
    </view>
  </view>
</page>

<style>
.aiui {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  min-height: 100vh;
  background: #050806;
  color: #eaffef;
}
.topbar {
  display: flex;
  flex-direction: row;
  justify-content: space-between;
  align-items: center;
}
.brand {
  color: #eaffef;
  font-size: 14px;
  font-weight: 700;
}
.version {
  color: #75ff93;
  font-size: 12px;
}
.statusCard {
  padding: 14px;
  border-radius: 14px;
  background: #102015;
  border: 1px solid #2b6f3a;
}
.mode {
  color: #75ff93;
  font-size: 12px;
}
.state {
  color: #ffffff;
  font-size: 14px;
  line-height: 18px;
  font-weight: 700;
}
.hint {
  color: #a6d9b0;
  font-size: 12px;
  line-height: 13px;
}
.card {
  padding: 12px;
  border-radius: 12px;
  background: #0b110d;
  border: 1px solid #1f3325;
}
.grow {
  min-height: 140px;
}
.label {
  color: #75ff93;
  font-size: 12px;
}
.primary {
  color: #ffffff;
  font-size: 12px;
  line-height: 16px;
}
.secondary {
  color: #9fcfaa;
  font-size: 12px;
  line-height: 13px;
}
.statusText {
  color: #d9ffe0;
  font-size: 12px;
  line-height: 13px;
}
.answer {
  color: #ffffff;
  font-size: 12px;
  line-height: 14.7px;
}
.footer {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.footerText {
  color: #6f9878;
  font-size: 12px;
  line-height: 12px;
}
</style>
