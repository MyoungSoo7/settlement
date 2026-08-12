import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.tsx'
import UpdatePrompt from './components/UpdatePrompt'
import { registerServiceWorker } from './lib/serviceWorkerUpdate'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {/* 새 버전 알림 — 라우트와 무관한 앱 전역 사건이라 라우터 바깥에 둔다(컨텍스트 의존 없음). */}
    <UpdatePrompt />
    <App />
  </React.StrictMode>,
)

// 서비스워커 등록 — 예전에는 index.html 인라인 스크립트가 했으나, 새 버전 대기 상태를 화면(UpdatePrompt)에
// 전달해야 하므로 앱 코드로 옮겼다. 렌더를 막지 않도록 마운트 뒤에 부른다.
registerServiceWorker()
