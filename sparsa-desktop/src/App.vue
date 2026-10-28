<script setup>
/**
 * 根组件：只做两件事 —— 启动引导 + 承载路由。
 * 不做布局，布局在 MainLayout，保证以后可以加「启动页 / 引导向导」这类无侧栏页面。
 */
import { onMounted } from 'vue'
import { useAppStore } from '@/stores/app'

const app = useAppStore()

onMounted(() => {
  app.boot()
})
</script>

<template>
  <router-view />
</template>
