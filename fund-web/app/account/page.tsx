'use client';
import { FormEvent, useEffect, useState } from 'react';
import AppShell from '../../components/AppShell';
import { api } from '../../lib/session';

type Question = { id: string; prompt: string; choices: { value: number; label: string }[] };
type Profile = {
  riskLevel?: string;
  riskProfile?: string;
  completedAt?: string;
  assessmentDate?: string;
  [key: string]: unknown;
};

/**
 * 展示已有风险画像，并提交完整问卷。
 * 问卷接口失败时顶栏提示登录或错误，题目区仍显示“正在加载问卷…”，和真正的加载中无法区分。已有画像接口失败时静默留空，不影响填表。
 */
export default function AccountPage() {
  const [questions, setQuestions] = useState<Question[]>([]);
  const [answers, setAnswers] = useState<Record<string, number>>({});
  const [profile, setProfile] = useState<Profile | null>(null);
  const [notice, setNotice] = useState('风险画像来自问卷评分，用于帮助理解研究内容');
  /**
   * 分别拉取题目和已有画像。
   * 题目 4xx/5xx 或网络失败只改顶栏，题目列表保持为空；画像失败被忽略，页面继续显示“尚未完成评估”。
   */
  useEffect(() => {
    api('/api/v1/risk-questionnaire')
      .then((q) => setQuestions(q.questions ?? []))
      .catch((x) => setNotice(x instanceof Error ? x.message : '请先登录后填写问卷'));
    api('/api/v1/users/me/risk-profile')
      .then(setProfile)
      .catch(() => undefined);
  }, []);
  /**
   * 全部题目都有答案时才提交保存。
   * 有空题时停在本地提示，不发请求；接口 4xx/5xx 时保留已选答案，顶栏显示接口文案或“保存画像失败”。
   */
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (questions.some((q) => answers[q.id] == null)) {
      setNotice('请完成全部题目后再保存');
      return;
    }
    try {
      const result = await api('/api/v1/users/me/risk-profile', {
        method: 'PUT',
        body: JSON.stringify({ questionnaireVersion: 'risk-questionnaire-v1', answers }),
      });
      setProfile(result);
      setNotice('风险画像已保存');
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '保存画像失败');
    }
  };
  const profileName = profile?.riskLevel ?? profile?.riskProfile ?? '尚未完成评估';
  const assessedAt = profile?.completedAt ?? profile?.assessmentDate;
  return (
    <AppShell notice={notice}>
      <section className="workspace profile-page">
        <p>RISK PROFILE</p>
        <h2>账户与风险画像</h2>
        <p className="page-intro">
          完成问卷后，研究结果可以更贴近你的风险承受范围。问卷结果不构成投资建议。
        </p>
        <div className="profile-card">
          <small>当前风险画像</small>
          <b>{String(profileName)}</b>
          <span>{assessedAt ? `评估日期：${String(assessedAt)}` : '完成问卷后展示评估日期'}</span>
        </div>
        <form className="questionnaire" onSubmit={submit}>
          <h3>风险承受能力问卷</h3>
          {questions.length ? (
            questions.map((q, index) => (
              <label key={q.id}>
                <span>
                  {index + 1}. {q.prompt}
                </span>
                <select
                  value={answers[q.id] ?? ''}
                  onChange={(e) => setAnswers((a) => ({ ...a, [q.id]: Number(e.target.value) }))}
                >
                  <option value="">请选择</option>
                  {q.choices.map((c) => (
                    <option key={c.value} value={c.value}>
                      {c.label}
                    </option>
                  ))}
                </select>
              </label>
            ))
          ) : (
            <div className="empty-panel">正在加载问卷…</div>
          )}
          <button disabled={!questions.length}>保存风险画像</button>
        </form>
      </section>
    </AppShell>
  );
}
