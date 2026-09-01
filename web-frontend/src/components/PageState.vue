<script setup lang="ts">
import { CircleAlert, Inbox, RefreshCw } from 'lucide-vue-next'

withDefaults(
  defineProps<{
    loading?: boolean
    error?: string
    empty?: boolean
    emptyTitle?: string
    emptyDescription?: string
  }>(),
  { loading: false, error: '', empty: false, emptyTitle: '暂无数据', emptyDescription: '当前筛选条件下没有可显示的内容' },
)

defineEmits<{ retry: [] }>()
</script>

<template>
  <div v-if="loading" class="state-panel" aria-live="polite">
    <el-skeleton :rows="6" animated />
  </div>
  <div v-else-if="error" class="state-panel state-message" role="alert">
    <CircleAlert :size="32" />
    <strong>数据加载失败</strong>
    <p>{{ error }}</p>
    <el-button :icon="RefreshCw" @click="$emit('retry')">重新加载</el-button>
  </div>
  <div v-else-if="empty" class="state-panel state-message">
    <Inbox :size="32" />
    <strong>{{ emptyTitle }}</strong>
    <p>{{ emptyDescription }}</p>
  </div>
  <slot v-else />
</template>
