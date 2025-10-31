export default function Dashboard() {
  return (
    <div className="container py-16">
      <h1 className="text-3xl font-bold">Your App</h1>
      <p className="text-white/70 mt-2">Manage your subscription and track usage.</p>

      <section className="mt-10 grid md:grid-cols-3 gap-6">
        <div className="bg-black/30 border border-white/10 rounded-xl p-6">
          <div className="text-white/60 text-sm">Subscription</div>
          <div className="mt-2 flex items-center gap-2">
            <span className="badge">basic</span>
            <span className="text-white/50 text-sm">Active</span>
          </div>
          <button className="btn mt-4">Upgrade</button>
        </div>

        <div className="bg-black/30 border border-white/10 rounded-xl p-6">
          <div className="text-white/60 text-sm">Daily Traffic</div>
          <div className="text-2xl font-bold mt-2">0.8<span className="text-base text-white/50"> / 3 GB</span></div>
          <div className="w-full h-2 bg-white/10 rounded mt-3">
            <div className="h-2 bg-accent rounded" style={{ width: '26%' }}></div>
          </div>
        </div>

        <div className="bg-black/30 border border-white/10 rounded-xl p-6">
          <div className="text-white/60 text-sm">API Token</div>
          <div className="mt-2 text-sm">••••••••••••••••••••••••</div>
          <button className="btn mt-4">Generate</button>
        </div>
      </section>

      <section className="mt-10">
        <h2 className="text-xl font-semibold">Quick Start</h2>
        <pre className="mt-3 bg-black/30 border border-white/10 rounded p-3 text-sm">
{`# Authenticate the CLI
port-buddy init {API_TOKEN}

# Expose an HTTP service
port-buddy 3000

# Expose a TCP service
port-buddy tcp 127.0.0.1:5432`}
        </pre>
      </section>
    </div>
  )
}
