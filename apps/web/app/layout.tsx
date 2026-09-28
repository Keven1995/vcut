import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Vcut | Cortes inteligentes",
  description: "Fundacao para transformar videos longos em cortes publicaveis."
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="pt-BR">
      <body>{children}</body>
    </html>
  );
}
