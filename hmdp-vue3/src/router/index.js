import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/index'
    },
    {
      path: '/login',
      name: 'LoginPage',
      component: () => import('@/views/login/LoginPage.vue')
    },
    {
      path: '/register',
      name: 'RegisterPage',
      component: () => import('@/views/register/RegisterPage.vue')
    },
    {
      path: '/index',
      name: 'indexPage',
      component: () => import('@/views/index.vue')
    },
    {
      path: '/shopList',
      component: () => import('@/views/shop/ShopList.vue')
    },
    {
      path: '/blogDetail/:id?', // 动态参数 `:id`
      component: () => import('@/views/blog/BlogDetail.vue')
    },
    {
      path: '/blogComments/:id',
      name: 'BlogCommentsPage',
      component: () => import('@/views/blog/BlogComments.vue')
    },
    {
      path: '/blogEdit',
      component: () => import('@/views/blog/BlogEdit.vue')
    },
    {
      path: '/InfoHtml',
      component: () => import('@/views/info/InfoHtml.vue')
    },
    {
      path: '/InfoEdit',
      component: () => import('@/views/info/InfoEdit.vue')
    },
    {
      path: '/InfoOther/:id?',
      component: () => import('@/views/info/InfoOther.vue')
    },
    {
      path: '/shopDetail/:id?',
      component: () => import('@/views/shop/ShopDetail.vue')
    },
    {
      path: '/navigation/:shopId',
      name: 'NavigationPage',
      component: () => import('@/views/shop/NavigationPage.vue')
    },
    {
      path: '/customerService',
      name: 'CustomerServicePage',
      component: () => import('@/views/service/CustomerService.vue')
    },
    {
      path: '/shopMap',
      name: 'ShopMapPage',
      component: () => import('@/views/map/ShopMap.vue')
    },
    {
      path: '/message',
      name: 'MessageCenterPage',
      component: () => import('@/views/message/MessageCenter.vue')
    }
  ]
})

export default router
