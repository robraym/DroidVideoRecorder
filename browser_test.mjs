import { setupAtlasRuntime } from 'file:///C:/Users/Robraym/.codex/plugins/cache/openai-bundled/browser-use/0.1.0-alpha1/scripts/browser-client.mjs';
await setupAtlasRuntime({ globals: globalThis, backend: 'iab' });
await agent.browser.nameSession('📹 YouTube upload demo');
const tabs = await agent.browser.tabs.list();
console.log(JSON.stringify(tabs, null, 2));
