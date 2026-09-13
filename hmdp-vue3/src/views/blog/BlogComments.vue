<script setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ChatSquare } from '@element-plus/icons-vue'
import request from '@/utils/request'

const router = useRouter()
const route = useRoute()

// 数据定义
const blog = ref({})
const comments = ref([])
const content = ref('')
const sending = ref(false)
const loading = ref(true)

onMounted(() => {
  loadBlog()
  loadComments()
})

// 加载笔记基础信息（标题/封面，用于页头上下文）
const loadBlog = async () => {
  try {
    const { data } = await request.get('/blog/' + route.params.id)
    blog.value = data || {}
  } catch (e) {
    // 笔记加载失败不阻塞评论列表
  }
}

// 加载评论列表
const loadComments = async () => {
  try {
    const { data } = await request.get(`/blog-comments/of/blog/${route.params.id}`)
    comments.value = data || []
  } catch (e) {
    comments.value = []
  } finally {
    loading.value = false
  }
}

// 发表评论
const sendComment = async () => {
  const text = content.value.trim()
  if (!text) {
    ElMessage.warning('说点什么吧～')
    return
  }
  if (sending.value) return
  sending.value = true
  try {
    await request.post('/blog-comments', {
      blogId: route.params.id,
      content: text
    })
    content.value = ''
    ElMessage.success('评论成功')
    await loadComments()
  } catch (e) {
    // 拦截器已提示
  } finally {
    sending.value = false
  }
}

const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')
const goBack = () => router.back()
</script>

<template>
  <div class="bc-page">
    <div class="bc-header">
      <div class="bc-back" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </div>
      <div class="bc-title">评论</div>
      <div class="bc-header-right">
        <el-icon><ChatSquare /></el-icon>
        {{ comments.length }}
      </div>
    </div>

    <!-- 笔记上下文 -->
    <div class="bc-blog" v-if="blog.id">
      <img
        class="bc-blog-img"
        :src="
          blog.images
            ? '/src/assets' + blog.images.split(',')[0]
            : '/src/assets/imgs/blogs/blog1.jpg'
        "
        alt=""
        @error="
          (e) => {
            if (!e.target.dataset.fallback) {
              e.target.dataset.fallback = '1'
              e.target.src = '/src/assets/imgs/blogs/blog1.jpg'
            }
          }
        "
      />
      <div class="bc-blog-info">
        <div class="bc-blog-title">{{ blog.title }}</div>
        <div class="bc-blog-user">{{ blog.name || '点评用户' }}</div>
      </div>
    </div>

    <!-- 评论列表 -->
    <div class="bc-list">
      <div class="bc-item" v-for="c in comments" :key="c.id">
        <img
          class="bc-icon"
          :src="
            (c.icon ? '/src/assets' + c.icon : '') ||
            '/src/assets/imgs/icons/default-icon.png'
          "
          alt=""
          @error="
            (e) => {
              if (!e.target.dataset.fallback) {
                e.target.dataset.fallback = '1'
                e.target.src = '/src/assets/imgs/icons/default-icon.png'
              }
            }
          "
        />
        <div class="bc-item-main">
          <div class="bc-item-name">{{ c.name }}</div>
          <div class="bc-item-content">{{ c.content }}</div>
          <div class="bc-item-time">{{ formatTime(c.createTime) }}</div>
        </div>
      </div>
      <div v-if="!loading && comments.length === 0" class="bc-empty">
        抢个沙发，发表第一条评论吧～
      </div>
      <div v-if="loading" class="bc-empty">加载中...</div>
    </div>

    <!-- 输入区 -->
    <div class="bc-input-bar">
      <el-input
        v-model="content"
        placeholder="友善评论，理性发言～"
        maxlength="500"
        @keyup.enter="sendComment"
      />
      <el-button type="primary" class="bc-send" :loading="sending" @click="sendComment">
        发送
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.bc-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f6f8;
}
.bc-header {
  display: flex;
  align-items: center;
  padding: 12px 15px;
  background: #fff;
  border-bottom: 1px solid #f1f1f1;
  flex-shrink: 0;
}
.bc-back {
  font-size: 17px;
  margin-right: 10px;
  cursor: pointer;
}
.bc-title {
  flex: 1;
  font-size: 16px;
  font-weight: bold;
}
.bc-header-right {
  font-size: 13px;
  color: #999;
  display: flex;
  align-items: center;
  gap: 4px;
}
.bc-blog {
  display: flex;
  margin: 12px;
  padding: 10px;
  background: #fff;
  border-radius: 12px;
}
.bc-blog-img {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  object-fit: cover;
  background: #f2f2f2;
  flex-shrink: 0;
}
.bc-blog-info {
  margin-left: 10px;
  overflow: hidden;
}
.bc-blog-title {
  font-size: 14px;
  font-weight: bold;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bc-blog-user {
  margin-top: 4px;
  font-size: 12px;
  color: #999;
}
.bc-list {
  flex: 1;
  overflow-y: auto;
  margin: 0 12px;
  background: #fff;
  border-radius: 12px;
  padding: 4px 0;
}
.bc-item {
  display: flex;
  padding: 12px;
  border-bottom: 1px solid #f6f6f6;
}
.bc-item:last-child {
  border-bottom: none;
}
.bc-icon {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  object-fit: cover;
  background: #f2f2f2;
  flex-shrink: 0;
}
.bc-item-main {
  flex: 1;
  margin-left: 10px;
}
.bc-item-name {
  font-size: 13px;
  font-weight: bold;
  color: #555;
}
.bc-item-content {
  margin-top: 4px;
  font-size: 14px;
  color: #333;
  line-height: 1.6;
  word-break: break-all;
}
.bc-item-time {
  margin-top: 4px;
  font-size: 11px;
  color: #bbb;
}
.bc-empty {
  padding: 30px 0;
  text-align: center;
  color: #bbb;
  font-size: 12px;
}
.bc-input-bar {
  display: flex;
  gap: 8px;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #f1f1f1;
  flex-shrink: 0;
}
.bc-send {
  background: linear-gradient(to right, #f63, #f5b9a2);
  border: none;
}
</style>
