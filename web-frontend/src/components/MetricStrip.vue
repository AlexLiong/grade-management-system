<script setup lang="ts">
defineProps<{ items: Array<{ label: string; value: string | number; detail?: string; tone?: 'default' | 'success' | 'warning' | 'danger' }> }>()
</script>

<template>
  <div class="metric-strip">
    <div v-for="item in items" :key="item.label" :class="['metric-item', `tone-${item.tone || 'default'}`]">
      <span>{{ item.label }}</span>
      <strong>{{ item.value }}</strong>
      <small v-if="item.detail">{{ item.detail }}</small>
    </div>
  </div>
</template>
