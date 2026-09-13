<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Service, ArrowRight } from '@element-plus/icons-vue'
import FootBar from '@/components/FootBar.vue'
import { getUser } from '@/api/user'
import { getFollowList } from '@/api/service'

const router = useRouter()

const follows = ref([])
const logged = ref(false)

onMounted(async () => {
  try {
    // 先拿当前登录用户，再查我的关注列表
    const { data: user } = await getUser()
    logged.value = !!user
    const { data: list } = await getFollowList(user.id)
    follows.value = list || []
  } catch (e) {
    logged.value = false
  }
})

const toLogin = () => {
  ElMessage.info('请先登录')
  router.push('/login')
}

const toUserInfo = (id) => router.push(`/InfoOther?id=${id}`)
const toService = () => router.push('/customerService')
const goBack = () => router.back()
</script>

<template>
  <div class="msg-page">
    <div class="msg-header">
      <div class="msg-back" @click="goBack">
        <el-icon><ArrowRight style="transform: rotate(180deg)" /></el-icon>
      </div>
      <div class="msg-title">消息中心</div>
    </div>

    <!-- 官方客服入口 -->
    <div class="cs-entry" @click="toService">
      <div class="cs-avatar">
        <el-icon><Service /></el-icon>
      </div>
      <div class="cs-info">
        <div class="cs-name">商户推荐助手 · 小黑</div>
        <div class="cs-desc">想吃想玩拿不准？问我，帮你挑商户</div>
      </div>
      <el-icon class="cs-arrow"><ArrowRight /></el-icon>
    </div>

    <!-- 我的关注 -->
    <div class="section-title">我的关注</div>
    <div class="follow-list" v-if="logged">
      <div
        v-for="f in follows"
        :key="f.id"
        class="follow-row"
        @click="toUserInfo(f.id)"
      >
        <img
          class="follow-icon"
          :src="
            (f.icon ? '/src/assets' + f.icon : '') ||
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
        <div class="follow-info">
          <div class="follow-name">{{ f.nickName || f.name }}</div>
          <div class="follow-sub">查看主页与笔记</div>
        </div>
        <el-icon class="follow-arrow"><ArrowRight /></el-icon>
      </div>
      <div v-if="follows.length === 0" class="empty">
        还没有关注的人，去首页逛逛帖子和商户吧～
      </div>
    </div>
    <div v-else class="login-tip" @click="toLogin">
      登录后可查看关注动态与互动消息 &gt;
    </div>

    <FootBar :active-btn="3"></FootBar>
  </div>
</template>

<style scoped>
.msg-page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-bottom: 70px;
}
.msg-header {
  display: flex;
  align-items: center;
  padding: 12px 15px;
  background: #fff;
  border-bottom: 1px solid #f1f1f1;
}
.msg-back {
  font-size: 17px;
  color: #333;
  margin-right: 8px;
  cursor: pointer;
}
.msg-title {
  font-size: 16px;
  font-weight: bold;
  color: #333;
}
.cs-entry {
  display: flex;
  align-items: center;
  margin: 12px;
  padding: 12px;
  background: linear-gradient(to right, #fff7f2, #fff);
  border: 1px solid #ffd9c4;
  border-radius: 12px;
  cursor: pointer;
}
.cs-avatar {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: linear-gradient(to right, #f63, #f5b9a2);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}
.cs-info {
  flex: 1;
  margin-left: 10px;
}
.cs-name {
  font-size: 14px;
  font-weight: bold;
  color: #333;
}
.cs-desc {
  margin-top: 3px;
  font-size: 11px;
  color: #b08b74;
}
.cs-arrow {
  color: #d8b7a2;
}
.section-title {
  padding: 6px 15px;
  font-size: 13px;
  font-weight: bold;
  color: #666;
}
.follow-list {
  margin: 0 12px;
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
}
.follow-row {
  display: flex;
  align-items: center;
  padding: 11px 12px;
  border-bottom: 1px solid #f6f6f6;
  cursor: pointer;
}
.follow-row:last-child {
  border-bottom: none;
}
.follow-icon {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  background: #f2f2f2;
  flex-shrink: 0;
}
.follow-info {
  flex: 1;
  margin-left: 10px;
}
.follow-name {
  font-size: 14px;
  color: #333;
  font-weight: bold;
}
.follow-sub {
  margin-top: 2px;
  font-size: 11px;
  color: #aaa;
}
.follow-arrow {
  color: #ddd;
}
.empty {
  padding: 26px 0;
  text-align: center;
  color: #bbb;
  font-size: 12px;
}
.login-tip {
  margin: 12px;
  padding: 16px;
  text-align: center;
  background: #fff;
  border-radius: 12px;
  color: #f63;
  font-size: 13px;
  cursor: pointer;
}
</style>
