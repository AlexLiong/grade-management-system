<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { LockKeyhole, LogIn, ShieldCheck, UserRound } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { homeForUser } from '@/utils/permission'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
}
const errorMessage = computed(() => auth.error?.message)

async function submit(instance?: FormInstance) {
  if (!instance || !(await instance.validate().catch(() => false))) return
  try {
    const user = await auth.login(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
      ? route.query.redirect : homeForUser(user) || '/403'
    await router.replace(redirect)
  } catch {
    // Store exposes a normalized error for the inline alert.
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-identity" aria-label="系统标识">
      <div class="login-brand"><ShieldCheck :size="30" /></div>
      <p>长安大学 · 教务管理</p>
      <h1>高校成绩管理工作台</h1>
      <span>教师、学生与管理员统一入口</span>
    </section>
    <section class="login-panel">
      <div class="login-form-wrap">
        <div class="login-heading">
          <span>账号登录</span>
          <h2>欢迎回来</h2>
          <p>请使用学校分配的账号登录</p>
        </div>
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @keyup.enter="submit(formRef)">
          <el-form-item label="账号" prop="username">
            <el-input v-model="form.username" autocomplete="username" placeholder="学号或工号" :prefix-icon="UserRound" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" autocomplete="current-password" show-password placeholder="请输入密码" :prefix-icon="LockKeyhole" />
          </el-form-item>
          <el-button class="login-submit" type="primary" :icon="LogIn" :loading="auth.loading" @click="submit(formRef)">登录</el-button>
        </el-form>
        <p class="session-note"><ShieldCheck :size="14" /> 登录状态由安全会话管理</p>
      </div>
    </section>
  </main>
</template>
