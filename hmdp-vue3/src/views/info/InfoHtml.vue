<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElTabs, ElTabPane } from 'element-plus'
import { ArrowLeft, Edit, ChatDotRound } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores'
import { getUser, getUserBlog } from '@/api/user'
import {
  getFollowList,
  getFansList,
  updateIntroduce,
  getMyProfile,
  queryFollowFeed,
  updateMyBlog,
  deleteMyBlog
} from '@/api/service'
import {
  indexAddLike,
  indexQueryBlogById
} from '@/api/user'

const router = useRouter()
const userStore = useUserStore()

// 数据定义
const user = ref({})
const info = ref({})
const blogs = ref([])
const blogs2 = ref([]) // 关注的人的博客（收件箱feed）
const activeName = ref('1')
const params = reactive({
  minTime: 0, // 上一次拉取到的时间戳
  offset: 0 // 偏移量
})
const isReachBottom = ref(false)
// 关注/粉丝
const followUsers = ref([])
const fansCount = ref(0)
// 个性签名编辑
const introDialog = ref(false)
const introText = ref('')

// 笔记编辑/删除
const editBlogDialog = ref(false)
const editBlogForm = reactive({ id: null, title: '', content: '' })

const editBlog = (b) => {
  editBlogForm.id = b.id
  editBlogForm.title = b.title || ''
  editBlogForm.content = b.content || ''
  editBlogDialog.value = true
}

// 保存笔记修改（主页作品列表即时更新，软件主页热帖重新加载后同步）
const saveBlogEdit = () => {
  if (!editBlogForm.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  updateMyBlog(editBlogForm.id, {
    title: editBlogForm.title.trim(),
    content: editBlogForm.content
  })
    .then(() => {
      const b = blogs.value.find((x) => String(x.id) === String(editBlogForm.id))
      if (b) {
        b.title = editBlogForm.title.trim()
        if (editBlogForm.content) {
          b.content = editBlogForm.content
        }
      }
      editBlogDialog.value = false
      ElMessage.success('笔记已更新')
    })
    .catch(() => {
      ElMessage.error('保存失败，请重试')
    })
}

// 删除笔记
const removeBlog = (b) => {
  ElMessageBox.confirm(`确定删除笔记「${b.title}」吗？删除后主页不再展示`, '删除笔记', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => deleteMyBlog(b.id))
    .then(() => {
      blogs.value = blogs.value.filter((x) => String(x.id) !== String(b.id))
      ElMessage.success('已删除')
    })
    .catch(() => {})
}

// 生命周期钩子
onMounted(() => {
  queryUser()
})

// 方法定义
const queryUser = () => {
  // 获取用户信息
  getUser()
    .then(({ data }) => {
      // 保存用户
      user.value = data
      // 查询用户详情
      queryUserInfo()
      // 查询用户笔记
      queryBlogs()
      // 查询关注/粉丝统计与关注列表
      queryFollowStats()
    })
    .catch(() => {
      ElMessage.error('获取用户信息失败')
      router.push('/login')
    })
}

// 查询真实的关注列表、粉丝数（tab 标签与关注tab兜底内容使用）
const queryFollowStats = () => {
  getFollowList(user.value.id)
    .then(({ data }) => {
      followUsers.value = data || []
    })
    .catch(() => {})
  getFansList(user.value.id)
    .then(({ data }) => {
      fansCount.value = (data || []).length
    })
    .catch(() => {})
}

// 打开个性签名编辑弹窗
const editIntroduce = () => {
  introText.value = info.value.introduce || ''
  introDialog.value = true
}

// 保存个性签名
const saveIntroduce = () => {
  if (!introText.value.trim()) {
    ElMessage.warning('个性签名不能为空')
    return
  }
  updateIntroduce(introText.value.trim())
    .then(() => {
      info.value.introduce = introText.value.trim()
      userStore.setUserInfo(info.value)
      introDialog.value = false
      ElMessage.success('个性签名已更新')
    })
    .catch(() => {
      ElMessage.error('保存失败，请重试')
    })
}
const queryBlogs = () => {
  getUserBlog()
    .then(({ data }) => (blogs.value = data))
    .catch(() => {
      ElMessage.error('获取用户博客失败')
    })
}
const queryUserInfo = () => {
  // 查询用户详情（按 userId 正确路由，含个性签名）
  getMyProfile()
    .then(({ data }) => {
      if (!data) {
        return
      }
      // 保存用户详情
      info.value = data
      // 保存到本地
      userStore.setUserInfo(data)
    })
    .catch(() => {
      ElMessage.error('获取用户详情失败')
    })
  // 从本地获取用户详情
  // const userInfo = sessionStorage.getItem('userInfo')
  // if (userInfo) {
  //   info.value = JSON.parse(userInfo)
  // }
}

const queryBlogsOfFollow = (clear) => {
  if (clear) {
    params.offset = 0
    params.minTime = new Date().getTime() + 1
  }

  // 查询关注的人的博客（收件箱feed，需登录）
  queryFollowFeed({ lastId: params.minTime, offset: params.offset })
    .then(({ data }) => {
      if (!data || !data.list) {
        return
      }
      const { list, minTime, offset } = data
      list.forEach((b) => (b.img = b.images ? b.images.split(',')[0] : ''))
      blogs2.value = clear ? list : blogs2.value.concat(list)
      params.minTime = minTime
      params.offset = offset
    })
    .catch(() => {
      ElMessage.error('获取关注博客失败')
    })
}

// 个人主页左上角返回：固定回到软件主页面，避免按历史记录退回到发布页等中间页面
const goBack = () => {
  router.push('/')
}

// 图片加载失败时回退到本地占位图，避免库中脏数据或缺失文件导致裂图
const onImgError = (e, kind) => {
  const el = e.target
  if (el.dataset.fallback) {
    return
  }
  el.dataset.fallback = '1'
  el.src =
    kind === 'icon'
      ? '/src/assets/imgs/icons/default-icon.png'
      : '/src/assets/imgs/blogs/blog1.jpg'
}

const toEdit = () => {
  router.push('/infoEdit')
}

// 发布探店笔记（原底部导航中央的发布按钮移到个人主页）
const toPublish = () => {
  router.push('/blogEdit')
}

const logout = () => {
  // 退出登录
  userStore.resetUserInfo()
  router.push('/login')
}

const handleClick = (tab) => {
  if (tab.props.name === '4') {
    queryBlogsOfFollow(true)
  }
}

const addLike = (b) => {
  indexAddLike(b.id)
    .then(() => {
      queryBlogById(b)
    })
    .catch(() => {
      ElMessage.error('点赞失败')
      b.liked++
    })
}

const queryBlogById = (b) => {
  indexQueryBlogById(b.id)
    .then(({ data }) => {
      b.liked = data.liked
      b.isLike = data.isLike
    })
    .catch(() => {
      ElMessage.error('获取博客详情失败')
      b.liked++
    })
}

const onScroll = (e) => {
  let scrollTop = e.target.scrollTop
  let offsetHeight = e.target.offsetHeight
  let scrollHeight = e.target.scrollHeight
  if (scrollTop === 0) {
    // 到顶部了，查询一次
    queryBlogsOfFollow(true)
  } else if (
    scrollTop + offsetHeight + 1 > scrollHeight &&
    !isReachBottom.value
  ) {
    isReachBottom.value = true
    // 再次查询下一页数据
    queryBlogsOfFollow()
  } else {
    isReachBottom.value = false
  }
}
</script>

<template>
  <div class="info-html">
    <div class="header">
      <div class="header-back-btn" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </div>
      <div class="header-title">个人主页</div>
    </div>

    <div class="basic">
      <div class="basic-icon">
        <img
          :src="
            '/src/assets' + user.icon ||
            '/src/assets/imgs/icons/default-icon.png'
          "
          alt="用户头像"
        />
      </div>
      <div class="basic-info">
        <div class="name">{{ user.nickName }}</div>
        <span>杭州</span>
        <div class="edit-btn" @click="toEdit">编辑资料</div>
      </div>
      <div class="logout-btn" @click="logout">退出登录</div>
    </div>

    <div class="introduce" @click="editIntroduce">
      <span v-if="info.introduce">{{ info.introduce }} <el-icon><Edit /></el-icon></span>
      <span v-else
        >添加个人简介，让大家更好的认识你 <el-icon><Edit /></el-icon
      ></span>
    </div>

    <div class="content">
      <el-tabs v-model="activeName" @tab-click="handleClick">
        <el-tab-pane label="笔记" name="1">
          <div v-for="b in blogs" :key="b.id" class="blog-item">
            <div class="blog-img">
              <img
                :src="'/src/assets' + b.images.split(',')[0]"
                alt="博客图片"
                @error="onImgError($event, 'cover')"
              />
            </div>
            <div class="blog-info">
              <div class="blog-title">{{ b.title }}</div>
              <div class="blog-liked">
                <img src="/src/assets/imgs/thumbup.png" alt="点赞" />
                {{ b.liked }}
              </div>
              <div class="blog-comments">
                <el-icon><ChatDotRound /></el-icon> {{ b.comments }}
              </div>
            </div>
            <div class="blog-ops">
              <span @click="editBlog(b)">编辑</span>
              <span class="op-del" @click="removeBlog(b)">删除</span>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="评价" name="2">评价</el-tab-pane>
        <el-tab-pane :label="`粉丝(${fansCount})`" name="3"
          >粉丝({{ fansCount }})</el-tab-pane
        >
        <el-tab-pane :label="`关注(${followUsers.length})`" name="4">
          <div class="blog-list" @scroll="onScroll">
            <div class="blog-box" v-for="b in blogs2" :key="b.id">
              <div class="blog-img2" @click="router.push(`/blogDetail/${b.id}`)">
                <img
                  :src="'/src/assets' + b.img"
                  alt="博客图片"
                  @error="onImgError($event, 'cover')"
                />
              </div>
              <div class="blog-title">{{ b.title }}</div>
              <div class="blog-foot">
                <div class="blog-user-icon">
                  <img
                    :src="
                      '/src/assets' + b.icon ||
                      '/src/assets/imgs/icons/default-icon.png'
                    "
                    alt="用户头像"
                  />
                </div>
                <div class="blog-user-name">{{ b.name }}</div>
                <div class="blog-liked" @click="addLike(b)">
                  <svg
                    t="1646634642977"
                    class="icon"
                    viewBox="0 0 1024 1024"
                    version="1.1"
                    xmlns="http://www.w3.org/2000/svg"
                    p-id="2187"
                    width="14"
                    height="14"
                  >
                    <path
                      d="M160 944c0 8.8-7.2 16-16 16h-32c-26.5 0-48-21.5-48-48V528c0-26.5 21.5-48 48-48h32c8.8 0 16 7.2 16 16v448zM96 416c-53 0-96 43-96 96v416c0 53 43 96 96 96h96c17.7 0 32-14.3 32-32V448c0-17.7-14.3-32-32-32H96zM505.6 64c16.2 0 26.4 8.7 31 13.9 4.6 5.2 12.1 16.3 10.3 32.4l-23.5 203.4c-4.9 42.2 8.6 84.6 36.8 116.4 28.3 31.7 68.9 49.9 111.4 49.9h271.2c6.6 0 10.8 3.3 13.2 6.1s5 7.5 4 14l-48 303.4c-6.9 43.6-29.1 83.4-62.7 112C815.8 944.2 773 960 728.9 960h-317c-33.1 0-59.9-26.8-59.9-59.9v-455c0-6.1 1.7-12 5-17.1 69.5-109 106.4-234.2 107-364h41.6z m0-64h-44.9C427.2 0 400 27.2 400 60.7c0 127.1-39.1 251.2-112 355.3v484.1c0 68.4 55.5 123.9 123.9 123.9h317c122.7 0 227.2-89.3 246.3-210.5l47.9-303.4c7.8-49.4-30.4-94.1-80.4-94.1H671.6c-50.9 0-90.5-44.4-84.6-95l23.5-203.4C617.7 55 568.7 0 505.6 0z"
                      p-id="2188"
                      :fill="b.isLike ? '#ff6633' : '#82848a'"
                    ></path>
                  </svg>
                  {{ b.liked }}
                </div>
              </div>
            </div>
            <!-- 收件箱暂无新笔记时，兜底展示我关注的人，保证内容可用 -->
            <template v-if="blogs2.length === 0">
              <div class="follow-card" v-for="f in followUsers" :key="f.id" @click="router.push(`/InfoOther?id=${f.id}`)">
                <img
                  class="follow-icon"
                  :src="
                    (f.icon ? '/src/assets' + f.icon : '') ||
                    '/src/assets/imgs/icons/default-icon.png'
                  "
                  alt=""
                />
                <div class="follow-info">
                  <div class="follow-name">{{ f.nickName }}</div>
                  <div class="follow-sub">查看主页与笔记</div>
                </div>
              </div>
              <div v-if="followUsers.length === 0" class="feed-empty">
                还没有关注的人，去商户评论区关注感兴趣的人吧～
              </div>
              <div v-else class="feed-empty">
                关注的人发布新笔记后，会第一时间出现在这里
              </div>
            </template>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 个性签名编辑弹窗 -->
    <el-dialog v-model="introDialog" title="编辑个性签名" width="85%">
      <el-input
        v-model="introText"
        type="textarea"
        :rows="4"
        maxlength="200"
        show-word-limit
        placeholder="写一句话介绍自己吧～"
      />
      <template #footer>
        <el-button @click="introDialog = false">取消</el-button>
        <el-button type="primary" class="intro-save-btn" @click="saveIntroduce"
          >保存</el-button
        >
      </template>
    </el-dialog>

    <!-- 笔记编辑弹窗 -->
    <el-dialog v-model="editBlogDialog" title="编辑笔记" width="88%">
      <el-input
        v-model="editBlogForm.title"
        placeholder="标题"
        maxlength="50"
        show-word-limit
      />
      <el-input
        v-model="editBlogForm.content"
        type="textarea"
        :rows="5"
        placeholder="分享你的美食体验..."
        style="margin-top: 10px"
      />
      <template #footer>
        <el-button @click="editBlogDialog = false">取消</el-button>
        <el-button type="primary" class="intro-save-btn" @click="saveBlogEdit"
          >保存</el-button
        >
      </template>
    </el-dialog>

    <div class="foot-bar">
      <!-- 底部导航栏 -->
    </div>

    <!-- 发布按钮：从底部导航中央移入个人主页 -->
    <div class="publish-fab" @click="toPublish">
      <img class="publish-img" src="../../assets/imgs/add.png" alt="发布" />
      <span class="publish-text">发布</span>
    </div>
  </div>
</template>

<style scoped>
.info-html {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: #f5f5f5;
}

.header {
  position: relative;
  height: 44px;
  display: flex;
  align-items: center;
  padding: 0 15px;
  background: #fff;
  border-bottom: 1px solid #f1f1f1;
  flex-shrink: 0;
}

.header-back-btn {
  font-size: 16px;
  color: #333;
}

.header-title {
  flex: 1;
  text-align: center;
  font-size: 16px;
  font-weight: bold;
}

.basic {
  display: flex;
  align-items: center;
  padding: 15px;
  background: #fff;
  margin-top: 10px;
}

.basic-icon {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  overflow: hidden;
  margin-right: 15px;
}

.basic-icon img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.basic-info {
  flex: 1;
}

.name {
  font-size: 18px;
  font-weight: bold;
  margin-bottom: 5px;
}

.edit-btn {
  display: inline-block;
  padding: 5px 10px;
  background: #f5f5f5;
  border-radius: 15px;
  font-size: 12px;
  color: #666;
  margin-top: 5px;
}

.logout-btn {
  padding: 5px 10px;
  background: #f5f5f5;
  border-radius: 15px;
  font-size: 12px;
  color: #666;
}

.introduce {
  padding: 15px;
  background: #fff;
  margin-top: 10px;
  color: #999;
  font-size: 14px;
  cursor: pointer;
}
.introduce span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* 关注tab兜底：关注的人卡片 */
.follow-card {
  display: flex;
  align-items: center;
  padding: 12px 15px;
  border-bottom: 1px solid #f1f1f1;
  cursor: pointer;
}
.follow-icon {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  background: #f2f2f2;
}
.follow-info {
  margin-left: 10px;
}
.follow-name {
  font-size: 14px;
  font-weight: bold;
  color: #333;
}
.follow-sub {
  margin-top: 2px;
  font-size: 11px;
  color: #aaa;
}
.feed-empty {
  padding: 26px 15px;
  text-align: center;
  color: #bbb;
  font-size: 12px;
}
.intro-save-btn {
  background: #ff6633;
  border-color: #ff6633;
}

.content {
  flex: 1;
  overflow: hidden;
  background: #fff;
  margin-top: 10px;
}

:deep(.el-tabs__header) {
  margin: 0;
  height: 44px;
}

:deep(.el-tabs__content) {
  height: calc(100% - 44px);
  overflow-y: auto;
}

.blog-item {
  display: flex;
  padding: 15px;
  border-bottom: 1px solid #f1f1f1;
}

.blog-img {
  width: 80px;
  height: 80px;
  border-radius: 4px;
  overflow: hidden;
  margin-right: 10px;
}

.blog-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.blog-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.blog-title {
  font-size: 16px;
  font-weight: bold;
  margin-bottom: 10px;
}

.blog-liked,
.blog-comments {
  display: flex;
  align-items: center;
  font-size: 12px;
  color: #999;
}

.blog-liked img {
  width: 14px;
  height: 14px;
  margin-right: 5px;
}

.blog-list {
  height: 100%;
  overflow-y: auto;
}

.blog-box {
  padding: 15px;
  border-bottom: 1px solid #f1f1f1;
}

.blog-img2 {
  width: 100%;
  height: 200px;
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 10px;
}

.blog-img2 img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.blog-foot {
  display: flex;
  align-items: center;
  margin-top: 10px;
}

.blog-user-icon {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  overflow: hidden;
  margin-right: 10px;
}

.blog-user-icon img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.blog-user-name {
  flex: 1;
  font-size: 14px;
  color: #333;
}

.foot-bar {
  height: 50px;
  background: #fff;
  border-top: 1px solid #f1f1f1;
  flex-shrink: 0;
}

/* 发布悬浮按钮：原底部导航中央的发布按钮移入个人主页 */
.publish-fab {
  position: fixed;
  right: 20px;
  bottom: 70px;
  z-index: 998;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.publish-fab .publish-img {
  width: 38px;
  height: 38px;
  box-shadow: 0 0 3px 2px rgba(0, 0, 0, 0.1);
  border-radius: 18px;
}
.publish-fab .publish-text {
  margin-top: 2px;
  font-size: 11px;
  color: #f63;
  font-weight: bold;
}

/* 笔记编辑/删除操作 */
.blog-item {
  position: relative;
}
.blog-ops {
  position: absolute;
  right: 15px;
  top: 15px;
  display: flex;
  gap: 10px;
  font-size: 12px;
}
.blog-ops span {
  color: #f63;
  cursor: pointer;
}
.blog-ops .op-del {
  color: #999;
}
</style>
