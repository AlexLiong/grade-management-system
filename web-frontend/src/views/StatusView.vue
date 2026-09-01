<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, FileQuestion, ShieldX } from 'lucide-vue-next'

const props = defineProps<{ status: '403' | '404' }>()
const router = useRouter()
const content = computed(() =>
  props.status === '403'
    ? { icon: ShieldX, title: '无权访问', detail: '当前账号没有打开此页面所需的角色或权限。' }
    : { icon: FileQuestion, title: '页面不存在', detail: '链接可能已失效，或页面已经移动。' },
)
</script>

<template>
  <main class="standalone-state">
    <component :is="content.icon" :size="42" />
    <span>{{ status }}</span>
    <h1>{{ content.title }}</h1>
    <p>{{ content.detail }}</p>
    <el-button :icon="ArrowLeft" @click="router.back()">返回上一页</el-button>
  </main>
</template>
