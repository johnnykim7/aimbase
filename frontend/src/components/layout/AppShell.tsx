import { Outlet } from "react-router-dom";
import { Sidebar } from "./Sidebar";
import { COLORS } from "../../theme";

export const AppShell = () => (
  <div
    style={{
      display: "flex",
      height: "100vh",
      background: COLORS.bg,
      overflow: "hidden",
    }}
  >
    <Sidebar />
    <main
      style={{
        flex: 1,
        overflowY: "auto",
        padding: 32,
      }}
    >
      <Outlet />
    </main>
  </div>
);
