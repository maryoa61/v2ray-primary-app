# V2Ray Server Gateway Manager React Component

A premium, highly polished, dark-cyberpunk styled React component designed for creating, updating, and deleting V2Ray network configuration profiles. Perfect for integration with a client terminal dashboard.

---

## Key Capabilities

1. **Full CRUD States**: Easily add, edit, and delete gateway node configurations.
2. **Persistent Storage**: Leverages traditional `localStorage` to automatically sync configuration states across page reloads.
3. **Advanced Parameter Controls**: Supports configuring protocol drivers (`VLESS`, `VMESS`, `Shadowsocks`, `Trojan`), custom ports, stream transport network types (`ws`, `tcp`, `grpc`), TLS encryption toggles, server SNI overrides, and path variables.
4. **Interactive UI Inspector**: Live detail inspector showing complete parameters for the active connection node config.
5. **JSON Import/Export**: Trigger config backups via standalone JSON downloads and load subscription formats seamlessly.
6. **Built-in Mock Seeder**: If no profiles exist in the system cache, it seeds pristine sample nodes automatically on startup to serve as an interactive onboarding guide.

---

## How to Integrate with Your Project

### 1. Requirements

Ensure your project has the following configured:
* **React** >= 16.8+ (using Hooks)
* **Tailwind CSS** or any compatible utility styling setup.

### 2. Implementation Guide

Copy `/V2RayServerManager.jsx` into your React source workspace directory, e.g., `/src/components/V2RayServerManager.jsx`.

Then, import and render the component in your layout:

```jsx
import React from 'react';
import V2RayServerManager from './components/V2RayServerManager';

function App() {
  return (
    <div className="App">
      <V2RayServerManager />
    </div>
  );
}

export default App;
```

### 3. V2Ray Setup Parameters Schema

The component's core schema maps closely to classic client configurations:

```typescript
interface V2RayProfile {
  id: string;          // Format: 'v2ray-' + timestamp
  name: string;        // Readable gateway moniker
  type: string;        // 'VLESS', 'VMESS', 'SHADOWSOCKS', 'TROJAN'
  address: string;     // Outpoint Gateway host IP or domain name
  port: number;        // Endpoint port (typically 443)
  uuid: string;        // Auth UUID or Passphrase password
  network: string;     // 'ws' | 'tcp' | 'grpc'
  path?: string;       // Stream endpoint route matching (WS or gRPC service)
  tls: boolean;        // Security Layer Toggle
  sni?: string;        // SNI host override parameter
  latency?: number;    // Cache ping latency rating
}
```

---

*Formulated seamlessly for the V2Ray Dan ecosystem.*
