<script def>
{
  "navigationBarTitleText": "TG Glass Mockup",
  "description": "Telegram UI mockup for Rokid RG-glasses. Uses official Sprite Ink mini-program structure and Rokid design rules: 480x640 canvas, 480x400 safe area, black transparent background, #40FF5E line UI, keyboard/gesture-first operation.",
  "schema": {
    "data": {
      "type": "object",
      "properties": {}
    }
  }
}
</script>

<script setup>
const chats = [
  { name: 'Jane / Hermes', time: '09:42', preview: '先看 mockup，再接 TDLib live data', unread: '●' },
  { name: 'Rokid Dev', time: '09:18', preview: 'BT-PAN online · helper ready', unread: '○' },
  { name: 'Family', time: 'Yesterday', preview: '今晚吃饭?', unread: '○' }
];

const messages = [
  { peer: 'Jane', body: 'Telegram 放在眼鏡上：只顯示必要訊息。' },
  { peer: 'Me', body: '收到。Enter 語音，Swipe 切換，雙擊返回。' },
  { peer: 'Jane', body: 'UI 要符合 Rokid 安全區。' }
];

function safeText(value) {
  if (value === undefined || value === null) return '';
  return String(value);
}

export default {
  data: {
    screen: 'CHAT_LIST',
    title: 'Telegram',
    subtitle: 'CXR companion · TDLib on phone · 480×400 safe area',
    focusIndex: 0,
    focusA: '▶', focusB: ' ', focusC: ' ',
    row1Title: chats[0].name, row1Meta: chats[0].time, row1Preview: chats[0].preview, row1Unread: chats[0].unread,
    row2Title: chats[1].name, row2Meta: chats[1].time, row2Preview: chats[1].preview, row2Unread: chats[1].unread,
    row3Title: chats[2].name, row3Meta: chats[2].time, row3Preview: chats[2].preview, row3Unread: chats[2].unread,
    message1Peer: messages[0].peer, message1Body: messages[0].body,
    message2Peer: messages[1].peer, message2Body: messages[1].body,
    message3Peer: messages[2].peer, message3Body: messages[2].body,
    composer: 'Enter / two-finger double tap: voice → text',
    banner: '',
    help: '↑↓ choose · Enter open/voice · Back return',
    eventLog: 'ready'
  },

  onLoad() {
    this.renderChatList('onLoad');
  },

  handleTap() {
    this.advance('tap');
  },

  onKeyUp(event) {
    const code = safeText(event && (event.key || event.code || event.keyCode || event.which));
    if (code === 'ArrowDown' || code === 'DPAD_DOWN' || code === '20') {
      this.moveFocus(1, 'key-' + code);
      return;
    }
    if (code === 'ArrowUp' || code === 'DPAD_UP' || code === '19') {
      this.moveFocus(-1, 'key-' + code);
      return;
    }
    if (code === 'Enter' || code === 'NumpadEnter' || code === '13' || code === '66') {
      this.advance('key-' + code);
      return;
    }
    if (code === 'Backspace' || code === 'Escape' || code === '4') {
      this.back('key-' + code);
      return;
    }
    this.setData({ eventLog: 'key ' + code });
  },

  moveFocus(delta, reason) {
    if (this.data.screen !== 'CHAT_LIST') return;
    let next = this.data.focusIndex + delta;
    if (next < 0) next = 2;
    if (next > 2) next = 0;
    this.setData({ focusIndex: next, eventLog: reason });
    this.renderFocus(next);
  },

  renderFocus(index) {
    this.setData({
      focusA: index === 0 ? '▶' : ' ',
      focusB: index === 1 ? '▶' : ' ',
      focusC: index === 2 ? '▶' : ' '
    });
  },

  advance(reason) {
    const screen = this.data.screen;
    if (screen === 'CHAT_LIST') {
      this.renderChat(reason);
      return;
    }
    if (screen === 'CHAT') {
      this.renderComposer(reason);
      return;
    }
    if (screen === 'COMPOSER') {
      this.renderNotification(reason);
      return;
    }
    this.renderChatList(reason);
  },

  back(reason) {
    if (this.data.screen === 'CHAT_LIST') {
      this.setData({ eventLog: reason + ' · already at list' });
      return;
    }
    if (this.data.screen === 'CHAT') {
      this.renderChatList(reason);
      return;
    }
    if (this.data.screen === 'COMPOSER') {
      this.renderChat(reason);
      return;
    }
    this.renderChatList(reason);
  },

  selectedChat() {
    return chats[this.data.focusIndex] || chats[0];
  },

  renderChatList(reason) {
    this.setData({
      screen: 'CHAT_LIST',
      title: 'Telegram',
      subtitle: '3 chats · phone companion online via CXR',
      banner: '',
      composer: 'Enter: open · ↑↓: choose · Back: system',
      help: 'Safe band only: rows are 64px; no filled bubbles',
      eventLog: reason + ' · list'
    });
    this.renderFocus(this.data.focusIndex || 0);
  },

  renderChat(reason) {
    const chat = this.selectedChat();
    this.setData({
      screen: 'CHAT',
      title: chat.name,
      subtitle: 'CXR live data · media hidden until selected',
      banner: '',
      message1Peer: messages[0].peer, message1Body: messages[0].body,
      message2Peer: messages[1].peer, message2Body: messages[1].body,
      message3Peer: messages[2].peer, message3Body: messages[2].body,
      composer: 'Hold Enter / two-finger double tap: dictate reply',
      help: 'Own/right = 80% stroke · peer/left = 40% stroke',
      eventLog: reason + ' · chat'
    });
  },

  renderComposer(reason) {
    this.setData({
      screen: 'COMPOSER',
      title: 'Voice reply',
      subtitle: 'Phone ASR/TDLib over CXR · interim 50%',
      banner: 'LISTENING 7s · cancel with Back',
      message1Peer: 'Interim', message1Body: '我正在用眼鏡回覆…',
      message2Peer: 'Final', message2Body: '我正在用眼鏡回覆 Telegram。',
      message3Peer: 'Action', message3Body: 'Enter sends · Back returns to chat',
      composer: 'Discrete level meter: ▂ ▃ ▅ ▇ ▅ ▃',
      help: 'No big fills, no waveform blobs, no tiny text',
      eventLog: reason + ' · composer'
    });
  },

  renderNotification(reason) {
    this.setData({
      screen: 'NOTIFY',
      title: 'Incoming banner',
      subtitle: 'Off-chat notification in top cautious zone',
      banner: 'New · Rokid Dev: helper ready',
      message1Peer: 'Banner', message1Body: 'Top strip only, glanceable, not blocking chat.',
      message2Peer: 'Focus', message2Body: 'Enter opens; Back dismisses.',
      message3Peer: 'Rule', message3Body: 'No Android heads-up fill; use stroked card.',
      composer: 'Enter: return to list',
      help: 'Notification respects mute + current chat',
      eventLog: reason + ' · banner'
    });
  }
};
</script>

<page bindtap="handleTap">
  <view class="root">
    <view class="cautionTop">
      <text class="topTitle">{{ title }}</text>
      <text class="topSub">{{ subtitle }}</text>
      <text class="banner">{{ banner }}</text>
    </view>

    <view class="safeArea">
      <view class="listBlock">
        <view class="rowSelected">
          <text class="focusMark">{{ focusA }}</text>
          <text class="rowTitle">{{ row1Title }}</text>
          <text class="rowMeta">{{ row1Meta }} {{ row1Unread }}</text>
          <text class="rowPreview">{{ row1Preview }}</text>
        </view>
        <view class="rowNormal">
          <text class="focusMark">{{ focusB }}</text>
          <text class="rowTitle">{{ row2Title }}</text>
          <text class="rowMeta">{{ row2Meta }} {{ row2Unread }}</text>
          <text class="rowPreview">{{ row2Preview }}</text>
        </view>
        <view class="rowNormal">
          <text class="focusMark">{{ focusC }}</text>
          <text class="rowTitle">{{ row3Title }}</text>
          <text class="rowMeta">{{ row3Meta }} {{ row3Unread }}</text>
          <text class="rowPreview">{{ row3Preview }}</text>
        </view>
      </view>

      <view class="messageBlock">
        <view class="bubblePeer">
          <text class="msgPeer">{{ message1Peer }}</text>
          <text class="msgBody">{{ message1Body }}</text>
        </view>
        <view class="bubbleOwn">
          <text class="msgPeer">{{ message2Peer }}</text>
          <text class="msgBody">{{ message2Body }}</text>
        </view>
        <view class="bubblePeer">
          <text class="msgPeer">{{ message3Peer }}</text>
          <text class="msgBody">{{ message3Body }}</text>
        </view>
      </view>
    </view>

    <view class="cautionBottom">
      <text class="composer">{{ composer }}</text>
      <text class="help">{{ help }}</text>
      <text class="debug">{{ screen }} · {{ eventLog }}</text>
    </view>
  </view>
</page>

<style>
.root {
  width: 480px;
  height: 640px;
  background: #000000;
  color: #40FF5E;
  display: flex;
  flex-direction: column;
}
.cautionTop {
  width: 480px;
  height: 120px;
  padding: 18px 18px 0 18px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}
.topTitle {
  color: #40FF5E;
  font-size: 32px;
  line-height: 40px;
}
.topSub, .banner, .help, .debug {
  color: rgba(64, 255, 94, 0.5);
  font-size: 16px;
  line-height: 22px;
}
.banner {
  color: #40FF5E;
  border: 2px solid #40FF5E;
  border-radius: 12px;
  padding: 2px 8px;
  margin-top: 4px;
}
.safeArea {
  width: 480px;
  height: 400px;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  padding: 0 12px;
}
.listBlock {
  width: 456px;
  height: 208px;
  display: flex;
  flex-direction: column;
}
.rowSelected, .rowNormal {
  width: 456px;
  height: 64px;
  border-radius: 12px;
  border: 2px solid rgba(64, 255, 94, 0.4);
  margin-bottom: 5px;
  box-sizing: border-box;
  padding: 4px 10px;
}
.rowSelected {
  border: 2px solid rgba(64, 255, 94, 0.8);
}
.focusMark {
  color: #40FF5E;
  font-size: 18px;
  line-height: 24px;
}
.rowTitle {
  color: #40FF5E;
  font-size: 20px;
  line-height: 26px;
}
.rowMeta, .rowPreview, .msgPeer {
  color: rgba(64, 255, 94, 0.5);
  font-size: 18px;
  line-height: 24px;
}
.messageBlock {
  width: 456px;
  height: 188px;
  display: flex;
  flex-direction: column;
}
.bubblePeer, .bubbleOwn {
  width: 420px;
  min-height: 52px;
  border-radius: 12px;
  border: 2px solid rgba(64, 255, 94, 0.4);
  margin-bottom: 6px;
  padding: 4px 10px;
  box-sizing: border-box;
}
.bubbleOwn {
  margin-left: 36px;
  border: 2px solid rgba(64, 255, 94, 0.8);
}
.msgBody {
  color: #40FF5E;
  font-size: 20px;
  line-height: 26px;
}
.cautionBottom {
  width: 480px;
  height: 120px;
  padding: 4px 18px 0 18px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}
.composer {
  color: #40FF5E;
  font-size: 20px;
  line-height: 26px;
  border: 2px solid rgba(64, 255, 94, 0.8);
  border-radius: 12px;
  padding: 4px 8px;
}
</style>
