import type { ReactNode } from "react";
import AppIcon from "./AppIcon";

/** 展示组件只接收页面状态，不请求接口，避免视觉调整改变业务行为。 */
export function StatePanel({
  title,
  children,
  loading = false,
  error = false,
  onRetry,
}: {
  title: string;
  children?: ReactNode;
  loading?: boolean;
  error?: boolean;
  onRetry?: () => void;
}) {
  return (
    <div
      className={`state-panel${error ? " is-error" : ""}`}
      role={error ? "alert" : "status"}
      aria-busy={loading}
    >
      <span className={`state-symbol${loading ? " is-loading" : ""}`}>
        <AppIcon name={error ? "shield" : "report"} size={24} />
      </span>
      <b>{title}</b>
      {children && <p>{children}</p>}
      {onRetry && (
        <button className="secondary" type="button" onClick={onRetry}>
          重新加载
        </button>
      )}
    </div>
  );
}

export function MetricCard({
  label,
  value,
  note,
  tone = "neutral",
}: {
  label: string;
  value: ReactNode;
  note: string;
  tone?: "neutral" | "up" | "down";
}) {
  return (
    <article className={`metric-card ${tone}`}>
      <small>{label}</small>
      <b>{value}</b>
      <em>{note}</em>
    </article>
  );
}

export function StatusTag({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: "neutral" | "active" | "error" | "success";
}) {
  return <span className={`status-tag status-${tone}`}>{children}</span>;
}

/** 金额缺失或无效时显示占位；真实的零仍然显示为 0.00。 */
export function amountText(value: unknown, signed = false) {
  if (value == null || value === "" || !Number.isFinite(Number(value)))
    return "—";
  const number = Number(value);
  return `${signed && number > 0 ? "+" : ""}${number.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export function valueTone(value: unknown): "neutral" | "up" | "down" {
  if (
    value == null ||
    value === "" ||
    !Number.isFinite(Number(value)) ||
    Number(value) === 0
  )
    return "neutral";
  return Number(value) > 0 ? "up" : "down";
}
