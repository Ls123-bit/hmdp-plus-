<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Location } from '@element-plus/icons-vue'
import FootBar from '@/components/FootBar.vue'
import request from '@/utils/request'

const router = useRouter()

// 数据定义
const shops = ref([])
const areas = ref(['全部'])
const activeArea = ref('全部')
const userLoc = ref(null) // { x: 经度, y: 纬度 }
const locating = ref(false)

onMounted(() => {
  loadShops()
  locate()
})

// 加载全量商户
const loadShops = async () => {
  try {
    const { data } = await request.get('/cs/shops')
    shops.value = data || []
    const set = new Set()
    shops.value.forEach((s) => s.area && set.add(s.area))
    areas.value = ['全部', ...set]
  } catch (e) {
    console.log('获取商户失败:', e)
  }
}

// 尝试浏览器定位（拒绝或失败不影响使用）
const locate = () => {
  if (!navigator.geolocation) {
    return
  }
  locating.value = true
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      userLoc.value = { x: pos.coords.longitude, y: pos.coords.latitude }
      locating.value = false
    },
    () => {
      locating.value = false
    },
    { timeout: 4000 }
  )
}

// 球面距离估算（经纬度 -> 米）
const distanceOf = (s) => {
  if (!userLoc.value || s.x == null || s.y == null) {
    return null
  }
  const rad = Math.PI / 180
  const dLat = (s.y - userLoc.value.y) * rad
  const dLng = (s.x - userLoc.value.x) * rad
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(userLoc.value.y * rad) *
      Math.cos(s.y * rad) *
      Math.sin(dLng / 2) ** 2
  return Math.round(6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)))
}

const fmtDistance = (m) =>
  m == null ? '' : m < 1000 ? m + 'm' : (m / 1000).toFixed(1) + 'km'

// 按商圈过滤 + 排序（有定位按距离，否则按评分）
const displayShops = computed(() => {
  let list = shops.value.filter(
    (s) => activeArea.value === '全部' || s.area === activeArea.value
  )
  const withDist = list.map((s) => ({ ...s, _dist: distanceOf(s) }))
  if (userLoc.value) {
    withDist.sort(
      (a, b) =>
        (a._dist ?? Infinity) - (b._dist ?? Infinity) ||
        (b.score ?? 0) - (a.score ?? 0)
    )
  } else {
    withDist.sort(
      (a, b) => (b.score ?? 0) - (a.score ?? 0) || (b.sold ?? 0) - (a.sold ?? 0)
    )
  }
  return withDist
})

// 示意图坐标：把商户经纬度映射到 320x220 的画布上
const mapPins = computed(() => {
  const pts = shops.value.filter((s) => s.x != null && s.y != null)
  if (pts.length === 0) {
    return []
  }
  const xs = pts.map((s) => s.x)
  const ys = pts.map((s) => s.y)
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  const minY = Math.min(...ys)
  const maxY = Math.max(...ys)
  const spanX = maxX - minX || 0.01
  const spanY = maxY - minY || 0.01
  return pts.map((s) => ({
    ...s,
    px: 24 + ((s.x - minX) / spanX) * 272,
    py: 20 + (1 - (s.y - minY) / spanY) * 180
  }))
})

const scoreText = (s) =>
  s.score == null ? '-' : (s.score / 10).toFixed(1)

const toDetail = (id) => router.push(`/shopDetail/${id}`)
</script>

<template>
  <div class="map-page">
    <div class="map-header">
      <div class="map-title">附近商户</div>
      <div class="map-sub">
        <el-icon><Location /></el-icon>
        {{ locating ? '定位中...' : userLoc ? '已按距离排序' : '未定位，按评分排序' }}
      </div>
    </div>

    <!-- 商户分布示意图 -->
    <div class="map-canvas">
      <svg viewBox="0 0 320 220" class="map-svg">
        <defs>
          <pattern id="grid" width="32" height="22" patternUnits="userSpaceOnUse">
            <path d="M 32 0 L 0 0 0 22" fill="none" stroke="#f0e8e2" stroke-width="1" />
          </pattern>
        </defs>
        <rect width="320" height="220" fill="url(#grid)" />
        <g
          v-for="p in mapPins"
          :key="p.id"
          class="map-pin"
          @click="toDetail(p.id)"
        >
          <circle :cx="p.px" :cy="p.py" r="10" fill="#ff6633" opacity="0.18" />
          <circle :cx="p.px" :cy="p.py" r="4.5" fill="#ff6633" />
          <text :x="p.px" :y="p.py - 9" text-anchor="middle" class="pin-label">
            {{ p.name.length > 6 ? p.name.slice(0, 6) + '…' : p.name }}
          </text>
        </g>
      </svg>
      <div class="map-tip">点击图钉查看商户详情</div>
    </div>

    <!-- 商圈筛选 -->
    <div class="area-bar">
      <span
        v-for="a in areas"
        :key="a"
        class="area-chip"
        :class="{ active: activeArea === a }"
        @click="activeArea = a"
        >{{ a }}</span
      >
    </div>

    <!-- 商户列表 -->
    <div class="shop-list">
      <div
        v-for="s in displayShops"
        :key="s.id"
        class="shop-row"
        @click="toDetail(s.id)"
      >
        <img class="shop-cover" :src="s.images ? s.images.split(',')[0] : ''" alt="" />
        <div class="shop-info">
          <div class="shop-name">{{ s.name }}</div>
          <div class="shop-meta">
            <span class="score">★ {{ scoreText(s) }}</span>
            <span>￥{{ s.avgPrice }}/人</span>
            <span>{{ s.area }}</span>
            <span v-if="s._dist != null" class="dist">{{ fmtDistance(s._dist) }}</span>
          </div>
          <div class="shop-addr">{{ s.address }}</div>
        </div>
      </div>
      <div v-if="displayShops.length === 0" class="empty">该商圈暂无商户</div>
    </div>

    <FootBar :active-btn="2"></FootBar>
  </div>
</template>

<style scoped>
.map-page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-bottom: 70px;
}
.map-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 14px 15px 10px;
  background: #fff;
}
.map-title {
  font-size: 17px;
  font-weight: bold;
  color: #333;
}
.map-sub {
  font-size: 11px;
  color: #999;
  display: flex;
  align-items: center;
  gap: 2px;
}
.map-canvas {
  margin: 10px 12px;
  background: #fff;
  border-radius: 12px;
  padding: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.map-svg {
  width: 100%;
  display: block;
}
.map-pin {
  cursor: pointer;
}
.pin-label {
  font-size: 8px;
  fill: #8a6a55;
}
.map-tip {
  text-align: center;
  font-size: 10px;
  color: #c0b4a8;
  padding: 2px 0 4px;
}
.area-bar {
  display: flex;
  gap: 8px;
  padding: 4px 12px 8px;
  overflow-x: auto;
}
.area-chip {
  flex-shrink: 0;
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 13px;
  background: #fff;
  color: #666;
  border: 1px solid #eee;
}
.area-chip.active {
  background: #ff6633;
  border-color: #ff6633;
  color: #fff;
}
.shop-list {
  padding: 0 12px;
}
.shop-row {
  display: flex;
  background: #fff;
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  cursor: pointer;
}
.shop-cover {
  width: 76px;
  height: 76px;
  border-radius: 8px;
  object-fit: cover;
  flex-shrink: 0;
  background: #f2f2f2;
}
.shop-info {
  flex: 1;
  margin-left: 10px;
  overflow: hidden;
}
.shop-name {
  font-size: 14px;
  font-weight: bold;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.shop-meta {
  margin-top: 4px;
  font-size: 11px;
  color: #999;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.score {
  color: #f63;
  font-weight: bold;
}
.dist {
  color: #2a9d5c;
}
.shop-addr {
  margin-top: 3px;
  font-size: 11px;
  color: #999;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.empty {
  text-align: center;
  color: #bbb;
  font-size: 13px;
  padding: 30px 0;
}
</style>
