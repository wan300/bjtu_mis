<script setup>
import { NConfigProvider, NDialogProvider, NMessageProvider } from 'naive-ui'

const themeOverrides = {
  common: {
    primaryColor: '#b34f1f',
    primaryColorHover: '#c95a2a',
    primaryColorPressed: '#8c3410',
    primaryColorSuppl: '#b34f1f',
    borderRadius: '12px',
    fontFamily: '"Segoe UI Variable", "Microsoft YaHei UI", "PingFang SC", sans-serif'
  },
  Button: {
    borderRadiusMedium: '999px',
    borderRadiusLarge: '999px'
  },
  Card: {
    borderRadius: '18px',
    paddingMedium: '18px'
  },
  Layout: {
    color: 'rgba(245, 239, 228, 0.6)'
  },
  Menu: {
    itemTextColor: '#776b5d',
    itemTextColorHover: '#2c261f',
    itemTextColorActive: '#2c261f',
    itemTextColorChildActive: '#2c261f',
    itemColorActive: 'rgba(179, 79, 31, 0.12)',
    itemColorActiveHover: 'rgba(179, 79, 31, 0.16)',
    itemIconColor: '#776b5d',
    itemIconColorHover: '#2c261f',
    itemIconColorActive: '#b34f1f',
    itemIconColorChildActive: '#b34f1f',
    arrowColor: '#776b5d',
    arrowColorActive: '#b34f1f'
  },
  DataTable: {
    thColor: 'rgba(179, 79, 31, 0.08)',
    tdColor: 'transparent',
    borderRadius: '16px'
  },
  Table: {
    thColor: 'rgba(179, 79, 31, 0.08)',
    tdColor: 'transparent',
    borderColor: 'rgba(78, 62, 43, 0.12)',
    borderRadius: '16px'
  },
  Input: {
    borderRadius: '16px'
  },
  Select: {
    peers: { InternalSelection: { borderRadius: '16px' } }
  },
  Tag: {
    borderRadius: '999px'
  },
  Alert: {
    borderRadius: '16px'
  },
  Collapse: {
    titleFontWeight: '600'
  }
}
</script>

<template>
  <NConfigProvider :theme-overrides="themeOverrides" :inline-theme-disabled="true">
    <NMessageProvider>
      <NDialogProvider>
        <router-view />
      </NDialogProvider>
    </NMessageProvider>
  </NConfigProvider>
</template>
