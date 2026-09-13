import request from '@/utils/request'

// 智能商户推荐客服：发送消息（大模型生成可能较慢，单独放宽超时）
export const chatWithAssistant = (data) =>
  request.post('/cs/chat', data, { timeout: 65000 })

// 智能商户推荐客服：查询服务状态（大模型是否在线）
export const getAssistantStatus = () => request.get('/cs/status')

// 全量商户列表（附近商户地图页）
export const getAllShops = () => request.get('/cs/shops')

// 商户下的探店笔记（商户详情页网友评价）
export const getShopReviews = (shopId) =>
  request.get('/cs/blogs', { params: { shopId } })

// 某用户的关注列表（消息中心-我的关注）
export const getFollowList = (userId) => request.get(`/cs/follows/${userId}`)

// 某用户的粉丝列表（个人主页-粉丝数）
export const getFansList = (userId) => request.get(`/cs/fans/${userId}`)

// 修改当前登录用户的个性签名（需登录）
export const updateIntroduce = (introduce) =>
  request.post('/profile/introduce', { introduce })

// 当前登录用户的资料（按 userId 查询，含个性签名）
export const getMyProfile = () => request.get('/cs/profile')

// 关注的人的笔记收件箱（滚动分页，需登录）
export const queryFollowFeed = (params) =>
  request.get('/blog/of/follow', { params })

// 关注/取消关注商户（切换，返回最新状态）
export const followShop = (shopId) =>
  request.put(`/profile/shop/${shopId}/follow`)

// 查询是否已关注某商户
export const isShopFollowed = (shopId) =>
  request.get(`/profile/shop/${shopId}/followed`)

// 修改自己的探店笔记
export const updateMyBlog = (id, data) =>
  request.post(`/profile/blog/${id}`, data)

// 删除自己的探店笔记
export const deleteMyBlog = (id) =>
  request.delete(`/profile/blog/${id}`)
