<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { chatWithAssistant, getAssistantStatus } from '@/api/service'
import { ArrowLeft, Promotion, Service, User } from '@element-plus/icons-vue'

const router = useRouter()

// 会话数据
const messages = ref([])
const input = ref('')
const sending = ref(false)
const llmOnline = ref(false)
const msgList = ref(null)

// 快捷提问
const quickQuestions = [
  '推荐一家评分高的美食店',
  '人均80以内的美食',
  '想唱歌，找家KTV',
  '有什么优惠活动'
]

onMounted(() => {
  // 欢迎语
  messages.value.push({
    role: 'bot',
    text:
      '您好，我是商户推荐助手 小黑 🤖\n' +
      '告诉我您的偏好，我来帮您挑商户：\n例如“推荐一家评分高的美食店”“人均100以内的火锅”。',
    shops: []
  })
  // 查询大模型状态，展示当前模式
  getAssistantStatus()
    .then(({ data }) => {
      llmOnline.value = !!(data && data.ollamaAvailable)
    })
    .catch(() => {
      llmOnline.value = false
    })
})

const scrollToBottom = () => {
  nextTick(() => {
    if (msgList.value) {
      msgList.value.scrollTop = msgList.value.scrollHeight
    }
  })
}

const send = async (text) => {
  const content = (text || input.value || '').trim()
  if (!content || sending.value) {
    return
  }
  input.value = ''
  messages.value.push({ role: 'user', text: content, shops: [] })
  scrollToBottom()

  sending.value = true
  messages.value.push({ role: 'bot', text: '', shops: [], loading: true })
  scrollToBottom()

  try {
    const { data } = await chatWithAssistant({
      message: content,
      history: messages.value
        .filter((m) => !m.loading && m.text)
        .slice(-6)
        .map((m) => ({ role: m.role === 'user' ? 'user' : 'assistant', content: m.text }))
    })
    const bot = messages.value[messages.value.length - 1]
    bot.text = data.reply || '抱歉，我暂时没有理解您的意思，换个说法试试？'
    bot.shops = data.shops || []
    bot.loading = false
    bot.mode = data.mode
  } catch (e) {
    const bot = messages.value[messages.value.length - 1]
    bot.loading = false
    bot.text = '网络开小差了，请稍后再试～'
    bot.shops = []
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

const goBack = () => router.back()
const toShopDetail = (id) => router.push(`/shopDetail/${id}`)
</script>

<template>
  <div class="cs-page">
    <!-- 头部 -->
    <div class="cs-header">
      <div class="cs-back" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </div>
      <div class="cs-header-main">
        <div class="cs-header-title">商户推荐助手</div>
        <div class="cs-header-sub">
          <span class="dot" :class="llmOnline ? 'on' : 'off'"></span>
          {{ llmOnline ? 'AI 智能推荐在线' : '智能推荐服务中' }}
        </div>
      </div>
      <div class="cs-avatar">
        <el-icon><Service /></el-icon>
      </div>
    </div>

    <!-- 消息列表 -->
    <div class="cs-body" ref="msgList">
      <div
        v-for="(m, idx) in messages"
        :key="idx"
        class="cs-msg"
        :class="m.role === 'user' ? 'right' : 'left'"
      >
        <div v-if="m.role === 'bot'" class="cs-msg-avatar">
          <el-icon><Service /></el-icon>
        </div>
        <div v-else class="cs-msg-avatar user-avatar">
          <el-icon><User /></el-icon>
        </div>
        <div class="cs-msg-main">
          <div class="cs-bubble" :class="{ loading: m.loading }">
            <template v-if="m.loading">
              <span class="typing"><i></i><i></i><i></i></span>
            </template>
            <template v-else>{{ m.text }}</template>
          </div>

          <!-- 推荐商户卡片 -->
          <div
            v-for="s in m.shops || []"
            :key="s.id"
            class="cs-shop-card"
            @click="toShopDetail(s.id)"
          >
            <img class="cs-shop-img" :src="s.images ? s.images.split(',')[0] : ''" alt="" />
            <div class="cs-shop-info">
              <div class="cs-shop-name">{{ s.name }}</div>
              <div class="cs-shop-meta">
                <span class="cs-shop-score">★ {{ s.score }}</span>
                <span>￥{{ s.avgPrice }}/人</span>
                <span>{{ s.area }}</span>
              </div>
              <div class="cs-shop-addr">{{ s.address }}</div>
              <div class="cs-shop-hours">营业 {{ s.openHours || '-' }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 快捷提问 -->
    <div class="cs-quick">
      <span
        v-for="q in quickQuestions"
        :key="q"
        class="cs-quick-item"
        @click="send(q)"
        >{{ q }}</span
      >
    </div>

    <!-- 输入区 -->
    <div class="cs-input-bar">
      <el-input
        v-model="input"
        placeholder="想吃什么、玩什么，问我吧～"
        size="large"
        clearable
        @keyup.enter="send()"
      />
      <el-button
        type="primary"
        class="cs-send-btn"
        :loading="sending"
        @click="send()"
      >
        <el-icon v-if="!sending"><Promotion /></el-icon>
        <template v-else>...</template>
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.cs-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f6f8;
}

.cs-header {
  display: flex;
  align-items: center;
  padding: 10px 15px;
  background: linear-gradient(to right, #f63, #f5b9a2);
  color: #fff;
  flex-shrink: 0;
}
.cs-back {
  font-size: 18px;
  margin-right: 10px;
  cursor: pointer;
}
.cs-header-main {
  flex: 1;
}
.cs-header-title {
  font-size: 16px;
  font-weight: bold;
}
.cs-header-sub {
  font-size: 11px;
  opacity: 0.9;
  margin-top: 2px;
  display: flex;
  align-items: center;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
  margin-right: 4px;
}
.dot.on {
  background: #2ecc71;
}
.dot.off {
  background: #ffe3d0;
}
.cs-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}

.cs-body {
  flex: 1;
  overflow-y: auto;
  padding: 15px 12px;
  box-sizing: border-box;
}
.cs-msg {
  display: flex;
  margin-bottom: 14px;
}
.cs-msg.right {
  flex-direction: row-reverse;
}
.cs-msg-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: linear-gradient(to right, #f63, #f5b9a2);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 16px;
}
.user-avatar {
  background: #c0c8d4;
}
.cs-msg-main {
  max-width: 78%;
  margin: 0 8px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.cs-msg.right .cs-msg-main {
  align-items: flex-end;
}
.cs-bubble {
  background: #fff;
  border-radius: 4px 12px 12px 12px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.7;
  color: #333;
  white-space: pre-wrap;
  word-break: break-all;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}
.cs-msg.right .cs-bubble {
  background: #ff6633;
  color: #fff;
  border-radius: 12px 4px 12px 12px;
}

/* 正在输入动画 */
.typing i {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 3px;
  border-radius: 50%;
  background: #f63;
  animation: blink 1.2s infinite both;
}
.typing i:nth-child(2) {
  animation-delay: 0.2s;
}
.typing i:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0% {
    opacity: 0.2;
  }
  50% {
    opacity: 1;
  }
  100% {
    opacity: 0.2;
  }
}

/* 商户卡片 */
.cs-shop-card {
  margin-top: 8px;
  display: flex;
  background: #fff;
  border-radius: 10px;
  padding: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  cursor: pointer;
}
.cs-shop-img {
  width: 72px;
  height: 72px;
  border-radius: 8px;
  object-fit: cover;
  flex-shrink: 0;
  background: #eee;
}
.cs-shop-info {
  flex: 1;
  margin-left: 8px;
  overflow: hidden;
}
.cs-shop-name {
  font-size: 14px;
  font-weight: bold;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cs-shop-meta {
  margin-top: 3px;
  font-size: 11px;
  color: #999;
  display: flex;
  gap: 8px;
}
.cs-shop-score {
  color: #f63;
  font-weight: bold;
}
.cs-shop-addr,
.cs-shop-hours {
  margin-top: 2px;
  font-size: 11px;
  color: #999;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 快捷提问 */
.cs-quick {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  overflow-x: auto;
  flex-shrink: 0;
  background: #f5f6f8;
}
.cs-quick-item {
  flex-shrink: 0;
  font-size: 12px;
  color: #f63;
  background: #fff;
  border: 1px solid #ffd2bd;
  border-radius: 14px;
  padding: 5px 10px;
  cursor: pointer;
}
.cs-quick-item:active {
  background: #fff3ec;
}

/* 输入区 */
.cs-input-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px calc(8px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #f1f1f1;
  flex-shrink: 0;
}
.cs-send-btn {
  background: linear-gradient(to right, #f63, #f5b9a2);
  border: none;
  font-size: 16px;
}
</style>
