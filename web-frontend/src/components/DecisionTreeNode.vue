<script setup lang="ts">
import type { DecisionNode } from '@/types/domain'

defineOptions({ name: 'DecisionTreeNode' })
defineProps<{ node: DecisionNode; depth?: number }>()
</script>

<template>
  <div class="tree-node-wrap">
    <div :class="['tree-node', { leaf: node.result }]">
      <small>{{ node.result ? '预测结论' : '判断条件' }}</small>
      <strong>{{ node.result || node.condition }}</strong>
    </div>
    <div v-if="node.yes || node.no" class="tree-branches">
      <div v-if="node.yes" class="tree-branch"><span>是</span><DecisionTreeNode :node="node.yes" :depth="(depth || 0) + 1" /></div>
      <div v-if="node.no" class="tree-branch"><span>否</span><DecisionTreeNode :node="node.no" :depth="(depth || 0) + 1" /></div>
    </div>
  </div>
</template>
