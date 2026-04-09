import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { apiClient } from "../api/client";

export default function Login() {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  // 로그인 페이지 진입 시 이전 세션 정보 클리어
  useEffect(() => {
    localStorage.removeItem("access_token");
    localStorage.removeItem("tenant_id");
  }, []);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const res = await apiClient.post("/auth/login", { email, password });
      const { access_token, tenant_id } = res.data.data;
      localStorage.setItem("access_token", access_token);
      if (tenant_id) {
        localStorage.setItem("tenant_id", tenant_id);
      }
      navigate("/", { replace: true });
      window.location.reload();
    } catch (err: any) {
      setError(err.message || "로그인 실패");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-background">
      <form onSubmit={handleLogin} className="w-[360px] p-8 bg-card rounded-xl border border-border">
        <h1 className="text-2xl font-bold text-foreground mb-2 text-center">Aimbase</h1>
        <p className="text-muted-foreground text-sm text-center mb-6">로그인</p>

        {error && (
          <div className="px-3 py-2 bg-destructive/10 text-destructive rounded-md text-[13px] mb-4">
            {error}
          </div>
        )}

        <label className="block text-[13px] text-muted-foreground mb-1">이메일</label>
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="w-full px-3 py-2.5 border border-border rounded-md text-sm font-mono mb-4 bg-background text-foreground focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20"
        />

        <label className="block text-[13px] text-muted-foreground mb-1">비밀번호</label>
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="w-full px-3 py-2.5 border border-border rounded-md text-sm font-mono mb-6 bg-background text-foreground focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20"
        />

        <button
          type="submit"
          disabled={loading}
          className="w-full py-2.5 bg-primary text-white border-none rounded-md text-sm font-semibold cursor-pointer disabled:opacity-70 disabled:cursor-wait hover:bg-primary/90 transition-colors"
        >
          {loading ? "로그인 중..." : "로그인"}
        </button>
      </form>
    </div>
  );
}
