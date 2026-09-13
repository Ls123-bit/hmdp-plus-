<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ArrowLeft, Location, Timer } from '@element-plus/icons-vue'
import request from '@/utils/request'

const router = useRouter()
const route = useRoute()

// 数据定义
const shop = ref({})
const userLoc = ref(null)
const navigating = ref(false)
const navProgress = ref(0) // 0~100 导航进度
let geoTimer = null
let navTimer = null

onMounted(() => {
  loadShop()
  locate()
})

onUnmounted(() => {
  clearInterval(geoTimer)
  clearInterval(navTimer)
})

// 加载商户
const loadShop = async () => {
  try {
    const { data } = await request.get('/shop/' + route.params.shopId)
    shop.value = data || {}
  } catch (e) {
    console.log('获取商户失败', e)
  }
}

// 尝试定位（失败不影响模拟）
const locate = () => {
  if (!navigator.geolocation) return
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      userLoc.value = { x: pos.coords.longitude, y: pos.coords.latitude }
    },
    () => {},
    { timeout: 4000 }
  )
}

// 球面距离（米）
const distanceMeters = computed(() => {
  if (!userLoc.value || shop.value.x == null || shop.value.y == null) return null
  const rad = Math.PI / 180
  const dLat = (shop.value.y - userLoc.value.y) * rad
  const dLng = (shop.value.x - userLoc.value.x) * rad
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(userLoc.value.y * rad) *
      Math.cos(shop.value.y * rad) *
      Math.sin(dLng / 2) ** 2
  return Math.round(6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)))
})

const fmtDistance = (m) =>
  m == null ? '约1.2km' : m < 1000 ? m + '米' : (m / 1000).toFixed(1) + 'km'

// 模拟驾车 30km/h
const etaText = computed(() => {
  const m = distanceMeters.value ?? 1200
  const minutes = Math.max(1, Math.round((m / 1000 / 30) * 60))
  return minutes + '分钟'
})

// 示意图坐标（我的位置固定在左下，商户按相对方位落点）
const pinPos = computed(() => {
  const sx = shop.value.x
  const sy = shop.value.y
  if (sx == null || sy == null) return { x: 250, y: 60 }
  if (!userLoc.value) return { x: 240, y: 70 }
  const dLng = sx - userLoc.value.x
  const dLat = sy - userLoc.value.y
  // 经纬度差异按比例压缩进画布
  const x = 60 + Math.max(-40, Math.min(40, dLng * 60)) + 120
  const y = 150 - Math.max(-40, Math.min(40, dLat * 60)) - 60
  return { x: Math.round(x), y: Math.round(y) }
})

// 开始/退出导航模拟
const startNav = () => {
  navigating.value = true
  navProgress.value = 0
  navTimer = setInterval(() => {
    navProgress.value += 2
    if (navProgress.value >= 100) {
      navProgress.value = 100
      clearInterval(navTimer)
    }
  }, 120)
}
const stopNav = () => {
  navigating.value = false
  navProgress.value = 0
  clearInterval(navTimer)
}

const goBack = () => router.back()
const toShopDetail = () => router.push(`/shopDetail/${route.params.shopId}`)
</script>

<template>
  <div class="nav-page">
    <div class="nav-header">
      <div class="nav-back" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </div>
      <div class="nav-title">模拟导航</div>
      <div class="nav-demo">演示模式</div>
    </div>

    <!-- 地图区域 -->
    <div class="nav-map">
      <svg viewBox="0 0 320 220" class="nav-svg">
        <defs>
          <pattern id="navgrid" width="32" height="22" patternUnits="userSpaceOnUse">
            <path d="M 32 0 L 0 0 0 22" fill="none" stroke="#e8f0e9" stroke-width="1" />
          </pattern>
          <linearGradient id="routeGrad" x1="0" y1="1" x2="1" y2="0">
            <stop offset="0%" stop-color="#4a90e2" />
            <stop offset="100%" stop-color="#ff6633" />
          </linearGradient>
        </defs>
        <rect width="320" height="220" fill="url(#navgrid)" />
        <!-- 模拟道路 -->
        <path d="M 40 180 L 40 100 L 150 100 L 150 40 L 260 40" fill="none" stroke="#dfe7df" stroke-width="10" />
        <path d="M 40 180 L 40 100 L 150 100 L 150 40 L 260 40" fill="none" stroke="#fff" stroke-width="1.5" stroke-dasharray="6 6" />
        <!-- 规划路线 -->
        <path
          d="M 40 180 L 40 100 L 150 100 L 150 40 L 260 40"
          fill="none"
          stroke="url(#routeGrad)"
          stroke-width="4"
          class="route-line"
        />
        <!-- 我的位置 -->
        <circle cx="40" cy="180" r="9" fill="#4a90e2" opacity="0.2" />
        <circle cx="40" cy="180" r="4.5" fill="#4a90e2" />
        <text x="40" y="198" text-anchor="middle" class="pin-label">我的位置</text>
        <!-- 商户位置 -->
        <circle :cx="pinPos.x" :cy="pinPos.y" r="10" fill="#ff6633" opacity="0.2" />
        <circle :cx="pinPos.x" :cy="pinPos.y" r="5" fill="#ff6633" />
        <text :x="pinPos.x" :y="pinPos.y - 12" text-anchor="middle" class="pin-label">
          {{ shop.name ? (shop.name.length > 6 ? shop.name.slice(0, 6) + '…' : shop.name) : '目的地' }}
        </text>
      </svg>
    </div>

    <!-- 路线信息 -->
    <div class="nav-info">
      <div class="nav-info-row">
        <span class="dot from"></span>
        我的位置
      </div>
      <div class="nav-info-row">
        <span class="dot to"></span>
        {{ shop.name || '目的地商户' }}
      </div>
      <div class="nav-meta">
        <div class="meta-item">
          <el-icon><Location /></el-icon>
          {{ shop.address || '-' }}
        </div>
        <div class="meta-item">
          <el-icon><Timer /></el-icon>
          全程约 {{ fmtDistance(distanceMeters) }}，驾车约 {{ etaText }}
        </div>
      </div>

      <!-- 导航状态 -->
      <div v-if="navigating" class="nav-status">
        <div class="nav-status-text">
          正在导航：沿当前道路行驶，剩余
          {{
            Math.max(
              0,
              Math.round(((distanceMeters ?? 1200) * (100 - navProgress)) / 100)
            )
          }}
          米
        </div>
        <el-progress
          :percentage="navProgress"
          :show-text="false"
          stroke-linecap="round"
        />
      </div>

      <el-button
        v-if="!navigating"
        type="primary"
        class="nav-start-btn"
        @click="startNav"
      >
        开始导航
      </el-button>
      <el-button v-else class="nav-stop-btn" @click="stopNav">退出导航</el-button>
      <div class="nav-tip" @click="toShopDetail">查看商户详情 &gt;</div>
    </div>
  </div>
</template>

<style scoped>
.nav-page {
  min-height: 100vh;
  background: #f5f6f8;
}
.nav-header {
  display: flex;
  align-items: center;
  padding: 12px 15px;
  background: #fff;
  border-bottom: 1px solid #f1f1f1;
}
.nav-back {
  font-size: 17px;
  margin-right: 10px;
  cursor: pointer;
}
.nav-title {
  flex: 1;
  font-size: 16px;
  font-weight: bold;
}
.nav-demo {
  font-size: 11px;
  color: #4a90e2;
  border: 1px solid #b5d4f2;
  border-radius: 10px;
  padding: 2px 8px;
}
.nav-map {
  margin: 12px;
  background: #fff;
  border-radius: 12px;
  padding: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.nav-svg {
  width: 100%;
  display: block;
}
.pin-label {
  font-size: 9px;
  fill: #555;
}
.route-line {
  stroke-dasharray: 10 6;
  animation: dash 1.2s linear infinite;
}
@keyframes dash {
  to {
    stroke-dashoffset: -16;
  }
}
.nav-info {
  margin: 0 12px;
  background: #fff;
  border-radius: 12px;
  padding: 14px;
}
.nav-info-row {
  display: flex;
  align-items: center;
  font-size: 14px;
  color: #333;
  padding: 4px 0;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 8px;
}
.dot.from {
  background: #4a90e2;
}
.dot.to {
  background: #ff6633;
}
.nav-meta {
  margin-top: 8px;
  padding: 10px;
  background: #f8f9fa;
  border-radius: 8px;
}
.meta-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #666;
  padding: 3px 0;
}
.nav-status {
  margin-top: 12px;
  padding: 10px;
  background: #eef6ee;
  border-radius: 8px;
}
.nav-status-text {
  font-size: 12px;
  color: #2a7a4b;
  margin-bottom: 8px;
}
.nav-start-btn {
  width: 100%;
  margin-top: 14px;
  background: linear-gradient(to right, #f63, #f5b9a2);
  border: none;
  border-radius: 20px;
}
.nav-stop-btn {
  width: 100%;
  margin-top: 14px;
  border-radius: 20px;
  color: #e74c3c;
}
.nav-tip {
  margin-top: 10px;
  text-align: center;
  font-size: 12px;
  color: #f63;
  cursor: pointer;
}
</style>
