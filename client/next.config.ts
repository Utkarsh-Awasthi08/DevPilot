import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  async rewrites() {
    const backendUrl = process.env.BACKEND_URL || "http://localhost:8080";
    
    return [
      {
        source: "/api/:path*",
        destination: `${backendUrl}/api/:path*`,
      },
      {
        source: "/oauth2/:path*",
        destination: `${backendUrl}/oauth2/:path*`,
      },
      {
        source: "/login/oauth2/:path*",
        destination: `${backendUrl}/login/oauth2/:path*`,
      }
    ];
  },
};

export default nextConfig;
