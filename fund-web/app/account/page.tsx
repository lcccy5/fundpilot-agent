"use client";
import { FormEvent, useEffect, useState } from "react";
import Link from "next/link";
import AppShell from "../../components/AppShell";
import { StatePanel } from "../../components/FinanceUI";
import { api } from "../../lib/session";
import { assessmentDate, firstUnanswered } from "../../lib/questionnaire.mjs";

type Question = {
  id: string;
  prompt: string;
  choices: { value: number; label: string }[];
};
type Profile = {
  riskLevel?: string;
  riskProfile?: string;
  [key: string]: unknown;
};

export default function AccountPage() {
  const [questions, setQuestions] = useState<Question[]>([]);
  const [answers, setAnswers] = useState<Record<string, number>>({});
  const [profile, setProfile] = useState<Profile | null>(null);
  const [user, setUser] = useState<{
    displayName?: string;
    username?: string;
  } | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [missingId, setMissingId] = useState<string | null>(null);
  const [notice, setNotice] = useState(
    "风险画像来自问卷评分，用于帮助理解研究内容",
  );

  // 账户、问卷、画像独立读取：画像失败时仍允许重新评估。
  useEffect(() => {
    let disposed = false;
    void api<{ questions: Question[] }>("/api/v1/risk-questionnaire")
      .then((result) => {
        if (!disposed) setQuestions(result.questions ?? []);
      })
      .catch((error) => {
        if (!disposed) {
          setFailed(true);
          setNotice(error instanceof Error ? error.message : "问卷暂不可用");
        }
      })
      .finally(() => {
        if (!disposed) setLoading(false);
      });
    void api<Profile>("/api/v1/users/me/risk-profile")
      .then((result) => {
        if (!disposed) setProfile(result);
      })
      .catch(() => {});
    void api<{ displayName?: string; username?: string }>("/api/v1/users/me")
      .then((result) => {
        if (!disposed) setUser(result);
      })
      .catch(() => {});
    return () => {
      disposed = true;
    };
  }, []);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (saving) return;
    const missing = firstUnanswered(questions, answers);
    if (missing) {
      setMissingId(missing);
      setNotice("请先完成标出的题目");
      // 只在提交时定位缺失项，避免打开账户页面就自动跳到问卷。
      const field = document.getElementById(`question-${missing}`);
      field?.scrollIntoView({ behavior: "smooth", block: "center" });
      field?.querySelector("select")?.focus({ preventScroll: true });
      return;
    }
    setSaving(true);
    try {
      setProfile(
        await api<Profile>("/api/v1/users/me/risk-profile", {
          method: "PUT",
          body: JSON.stringify({
            questionnaireVersion: "risk-questionnaire-v1",
            answers,
          }),
        }),
      );
      setMissingId(null);
      setNotice("风险画像已保存");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "保存画像失败");
    } finally {
      setSaving(false);
    }
  };
  const name =
    profile?.level ??
    profile?.riskLevel ??
    profile?.riskProfile ??
    "尚未完成评估";
  const levelNames: Record<string, string> = {
    CONSERVATIVE: "保守型",
    BALANCED: "平衡型",
    GROWTH: "成长型",
    AGGRESSIVE: "进取型",
  };
  const displayLevel = levelNames[String(name)] ?? String(name);
  const date = assessmentDate(profile);
  return (
    <AppShell notice={notice} title="我的账户" kicker="MY ACCOUNT">
      <section className="workspace profile-page">
        <p>ACCOUNT</p>
        <h2>{user?.displayName || user?.username || "我的账户"}</h2>
        <p className="page-intro">管理风险画像，查看你的研究与记录。</p>
        <div className="account-layout">
          <aside>
            <div className="profile-card">
              <small>当前风险画像</small>
              <b>{displayLevel}</b>
              <span>
                {date
                  ? `评估日期 ${String(date).slice(0, 10)}`
                  : "完成问卷后展示评估日期"}
              </span>
            </div>
            <nav className="account-links" aria-label="账户快捷入口">
              <Link href="/runs">
                研究任务 <span>→</span>
              </Link>
              <Link href="/reports">
                月度回顾 <span>→</span>
              </Link>
              <Link href="/notifications">
                通知中心 <span>→</span>
              </Link>
              <Link href="/compare">
                基金对比 <span>→</span>
              </Link>
              <Link href="/arena">
                多 Agent 研究 <span>→</span>
              </Link>
            </nav>
          </aside>
          <form className="questionnaire" onSubmit={submit}>
            <h3>风险承受能力问卷</h3>
            <p className="page-intro">问卷结果用于研究参考，不构成投资建议。</p>
            {loading ? (
              <StatePanel title="正在加载问卷" loading />
            ) : failed ? (
              <StatePanel title="问卷暂时加载失败" error>
                {notice}
              </StatePanel>
            ) : questions.length ? (
              questions.map((question, index) => (
                <label
                  key={question.id}
                  id={`question-${question.id}`}
                  className={question.id === missingId ? "unanswered" : ""}
                >
                  <span>
                    {index + 1}. {question.prompt}
                    {question.id === missingId ? " · 请完成这题" : ""}
                  </span>
                  <select
                    disabled={saving}
                    value={answers[question.id] ?? ""}
                    onChange={(event) => {
                      const value = event.target.value;
                      setAnswers((current) => {
                        const next = { ...current };
                        if (value === "") delete next[question.id];
                        else next[question.id] = Number(value);
                        return next;
                      });
                      if (missingId === question.id) setMissingId(null);
                    }}
                  >
                    <option value="">请选择</option>
                    {question.choices.map((choice) => (
                      <option key={choice.value} value={choice.value}>
                        {choice.label}
                      </option>
                    ))}
                  </select>
                </label>
              ))
            ) : (
              <StatePanel title="暂无可用问卷">
                问卷发布后可以在这里评估。
              </StatePanel>
            )}
            <button disabled={saving || loading || failed || !questions.length}>
              {saving ? "正在保存…" : "保存风险画像"}
            </button>
          </form>
        </div>
      </section>
    </AppShell>
  );
}
