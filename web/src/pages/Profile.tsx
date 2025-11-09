import { useAuth } from '../auth/AuthContext'
import { useEffect, useMemo, useState } from 'react'

const API_BASE = ((): string => {
  const env = (import.meta as any).env?.VITE_API_BASE?.toString()
  if (env) return env
  if (window.location.hostname === 'localhost' && window.location.port === '5173') return 'http://localhost:8080'
  return ''
})()

async function fetchJson(path: string, init?: RequestInit) {
  const res = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...(init?.headers || {}) },
    ...init,
  })
  if (!res.ok) throw new Error(await res.text().catch(()=>'') || `HTTP ${res.status}`)
  return res.json()
}

export default function Profile() {
  const { user, refresh } = useAuth()
  const [tokens, setTokens] = useState<Array<{id:string,label:string,createdAt:string,revoked:boolean,lastUsedAt?:string}>>([])
  const [loading, setLoading] = useState(false)
  const [newLabel, setNewLabel] = useState('cli')
  const [justCreatedToken, setJustCreatedToken] = useState<string|null>(null)
  const hasUser = useMemo(()=>!!user, [user])

  useEffect(()=>{
    if (!hasUser) return
    void loadTokens()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasUser])

  async function loadTokens() {
    setLoading(true)
    try {
      const list = await fetchJson('/api/tokens')
      setTokens(list)
    } catch (e) {
      // noop
    } finally {
      setLoading(false)
    }
  }

  async function createToken() {
    setLoading(true)
    try {
      const resp = await fetchJson('/api/tokens', { method: 'POST', body: JSON.stringify({ label: newLabel || 'cli' }) })
      setJustCreatedToken(resp.token as string)
      await loadTokens()
    } catch (e) {
      // noop
    } finally {
      setLoading(false)
    }
  }

  async function revokeToken(id: string) {
    setLoading(true)
    try {
      await fetch(`${API_BASE}/api/tokens/${id}`, { method: 'DELETE', credentials: 'include' })
      await loadTokens()
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="container py-16">
      <h1 className="text-3xl font-bold">Profile</h1>
      <p className="text-white/70 mt-2">Manage your account details.</p>

      <div className="mt-8 max-w-3xl bg-black/30 border border-white/10 rounded-xl p-6">
        <div className="flex items-center gap-4">
          {user?.avatarUrl ? (
            <img src={user.avatarUrl} alt="avatar" className="w-16 h-16 rounded-full border border-white/10" />
          ) : (
            <div className="w-16 h-16 rounded-full bg-white/10 grid place-items-center text-white/60">{user?.name?.[0] || user?.email?.[0] || '?'}</div>
          )}
          <div>
            <div className="text-lg font-semibold">{user?.name || 'Unknown User'}</div>
            <div className="text-white/60 text-sm">{user?.email}</div>
          </div>
        </div>

        <div className="mt-6 grid md:grid-cols-2 gap-4">
          <div className="bg-black/20 border border-white/10 rounded p-4">
            <div className="text-white/60 text-sm">Plan</div>
            <div className="mt-1 font-semibold capitalize">{user?.plan || 'basic'}</div>
          </div>
          <div className="bg-black/20 border border-white/10 rounded p-4">
            <div className="text-white/60 text-sm">User ID</div>
            <div className="mt-1 font-mono text-sm break-all">{user?.id}</div>
          </div>
        </div>

        <button className="btn mt-6" onClick={() => { void refresh() }}>Refresh</button>

        <div className="mt-10">
          <h2 className="text-xl font-semibold">API Tokens</h2>
          <p className="text-white/70 text-sm mt-1">Generate API tokens to authenticate the CLI using <span className="font-mono">port-buddy init {'{API_TOKEN}'}</span>.</p>

          <div className="mt-4 flex flex-col md:flex-row gap-3 md:items-end">
            <div className="flex-1">
              <label className="text-sm text-white/60">Label</label>
              <input value={newLabel} onChange={(e)=>setNewLabel(e.target.value)} className="mt-1 w-full bg-black/30 border border-white/10 rounded px-3 py-2" placeholder="e.g. laptop" />
            </div>
            <button className="btn" onClick={()=>{ void createToken() }} disabled={loading}>Generate Token</button>
          </div>

          {justCreatedToken && (
            <div className="mt-4 p-3 bg-emerald-500/10 border border-emerald-500/30 rounded">
              <div className="text-emerald-300 text-sm">New token created. Copy and store it now — it will not be shown again.</div>
              <div className="mt-2 font-mono break-all text-sm">{justCreatedToken}</div>
              <div className="mt-2 flex gap-2">
                <button className="btn" onClick={()=>{ navigator.clipboard.writeText(justCreatedToken).catch(()=>{}); }}>Copy</button>
                <button className="btn btn-secondary" onClick={()=>setJustCreatedToken(null)}>Dismiss</button>
              </div>
            </div>
          )}

          <div className="mt-6">
            {loading ? (
              <div className="text-white/60 text-sm">Loading tokens...</div>
            ) : tokens.length === 0 ? (
              <div className="text-white/60 text-sm">No tokens yet.</div>
            ) : (
              <div className="grid gap-2">
                {tokens.map(t => (
                  <div key={t.id} className="flex items-center justify-between bg-black/20 border border-white/10 rounded p-3">
                    <div>
                      <div className="font-mono text-sm">{t.label}</div>
                      <div className="text-white/50 text-xs">Created {new Date(t.createdAt).toLocaleString()} {t.lastUsedAt ? `• Last used ${new Date(t.lastUsedAt).toLocaleString()}` : ''}</div>
                    </div>
                    <div className="flex items-center gap-2">
                      {t.revoked ? (
                        <span className="badge">revoked</span>
                      ) : (
                        <button className="btn btn-secondary" onClick={()=>{ void revokeToken(t.id) }}>Revoke</button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
