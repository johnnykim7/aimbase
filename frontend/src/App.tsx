import { Navigate, Route, Routes } from "react-router-dom";
import { AppShell } from "./components/layout/AppShell";
import Dashboard from "./pages/Dashboard";
import Connections from "./pages/Connections";
import MCPServers from "./pages/MCPServers";
import Schemas from "./pages/Schemas";
import Policies from "./pages/Policies";
import Prompts from "./pages/Prompts";
import Workflows from "./pages/Workflows";
import Knowledge from "./pages/Knowledge";
import Auth from "./pages/Auth";
import Monitoring from "./pages/Monitoring";
import Tenants from "./pages/platform/Tenants";
import Subscriptions from "./pages/platform/Subscriptions";
import PlatformMonitoring from "./pages/platform/PlatformMonitoring";

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<Dashboard />} />
        <Route path="connections" element={<Connections />} />
        <Route path="mcp-servers" element={<MCPServers />} />
        <Route path="schemas" element={<Schemas />} />
        <Route path="policies" element={<Policies />} />
        <Route path="prompts" element={<Prompts />} />
        <Route path="workflows" element={<Workflows />} />
        <Route path="knowledge" element={<Knowledge />} />
        <Route path="auth" element={<Auth />} />
        <Route path="monitoring" element={<Monitoring />} />
        <Route path="platform">
          <Route index element={<Navigate to="tenants" replace />} />
          <Route path="tenants" element={<Tenants />} />
          <Route path="subscriptions" element={<Subscriptions />} />
          <Route path="monitoring" element={<PlatformMonitoring />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
