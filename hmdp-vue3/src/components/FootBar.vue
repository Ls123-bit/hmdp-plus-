<script setup>
import router from '@/router'
import { House, Location, ChatDotRound, User, Service } from '@element-plus/icons-vue'
defineProps({
  activeBtn: {
    type: Number,
    default: 1
  }
})
const toPage = (i) => {
  if (i === 0) {
    // 中央按钮：智能商户推荐客服
    router.push('/customerService')
  } else if (i === 4) {
    router.push('/InfoHtml')
  } else if (i === 1) {
    router.push('/')
  } else if (i === 2) {
    // 地图：附近商户
    router.push('/shopMap')
  } else if (i === 3) {
    // 消息：消息中心（客服消息 + 我的关注）
    router.push('/message')
  }
}
</script>

<template>
  <div class="foot">
    <div
      class="foot-box"
      :class="{ active: activeBtn === 1 }"
      @click="toPage(1)"
    >
      <div class="foot-view"><el-icon><House /></el-icon></div>
      <div class="foot-text">首页</div>
    </div>
    <div
      class="foot-box"
      :class="{ active: activeBtn === 2 }"
      @click="toPage(2)"
    >
      <div class="foot-view"><el-icon><Location /></el-icon></div>
      <div class="foot-text">地图</div>
    </div>
    <div class="foot-box" @click="toPage(0)">
      <div class="cs-btn">
        <el-icon><Service /></el-icon>
      </div>
      <div class="foot-text cs-text">智能客服</div>
    </div>
    <div
      class="foot-box"
      :class="{ active: activeBtn === 3 }"
      @click="toPage(3)"
    >
      <div class="foot-view"><el-icon><ChatDotRound /></el-icon></div>
      <div class="foot-text">消息</div>
    </div>
    <div
      class="foot-box"
      :class="{ active: activeBtn === 4 }"
      @click="toPage(4)"
    >
      <div class="foot-view"><el-icon><User /></el-icon></div>
      <div class="foot-text">我的</div>
    </div>
  </div>
</template>

<style scoped>
.foot-view :deep(.el-icon){
  font-size: 22px;
}
/* 中央智能客服按钮：渐变圆形，突出入口 */
.cs-btn {
  width: 38px;
  height: 38px;
  margin: 0 auto;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: linear-gradient(to right, #f63, #f5b9a2);
  box-shadow: 0 0 3px 2px rgba(0, 0, 0, 0.1);
}
.cs-btn :deep(.el-icon) {
  font-size: 20px;
}
.cs-text {
  color: #f63;
  font-weight: bold;
}
</style>
