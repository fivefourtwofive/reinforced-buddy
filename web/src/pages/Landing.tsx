import { Link } from 'react-router-dom'

export default function Landing() {
  return (
    <div>
      <section className="container py-20 md:py-28">
        <div className="grid md:grid-cols-2 gap-12 items-center">
          <div>
            <h1 className="text-4xl md:text-6xl font-extrabold leading-tight">
              Expose local ports to the internet in seconds
            </h1>
            <p className="text-white/70 mt-6 text-lg">
              Port Buddy is a simple, developer-friendly alternative to ngrok. Share your local HTTP and TCP services securely with a single command.
            </p>
            <div className="mt-8 flex gap-4">
              <Link to="/install" className="btn">Install CLI</Link>
              <a href="#pricing" className="btn">Pricing</a>
            </div>
            <p className="mt-4 text-xs text-white/50">Font: JetBrains Mono</p>
          </div>
          <div>
            <div className="bg-black/30 border border-white/10 rounded-xl p-6">
              <div className="text-sm text-white/60">Usage</div>
              <pre className="mt-3 bg-black/30 border border-white/10 rounded-lg p-4 overflow-auto text-sm">
{`# HTTP example
$ port-buddy 3000
http://localhost:3000 exposed to: https://random-subdomain.port-buddy.com

# TCP example (e.g., Postgres)
$ port-buddy tcp 127.0.0.1:5432
tcp 127.0.0.1:5432 exposed to: tcp-proxy-3.port-buddy.com:43452`}
              </pre>
            </div>
          </div>
        </div>
      </section>

      <section id="features" className="container py-16">
        <h2 className="text-2xl font-bold">Main Functionality</h2>
        <div className="grid md:grid-cols-3 gap-6 mt-6 text-white/80">
          <Feature title="HTTP & WebSocket tunneling" desc="Proxy HTTP(S) and WebSocket traffic to your local web app." />
          <Feature title="TCP proxying" desc="Expose databases and custom TCP services securely to the public internet." />
          <Feature title="Auth & subscriptions" desc="Sign in with Google/GitHub, manage API tokens, track usage." />
        </div>
      </section>

      <section id="use-cases" className="container py-16">
        <h2 className="text-2xl font-bold">Use Cases</h2>
        <ul className="grid md:grid-cols-2 gap-4 mt-6 list-disc list-inside text-white/80">
          <li>Share your in-progress web app with teammates or clients</li>
          <li>Test webhooks from third-party services on your local machine</li>
          <li>Grant temporary access to a database in your private network</li>
          <li>Demo features without deploying to staging</li>
        </ul>
      </section>

      <section id="pricing" className="container py-16">
        <h2 className="text-2xl font-bold">Pricing</h2>
        <div className="grid md:grid-cols-3 gap-6 mt-6">
          <PlanCard name="basic" price="$5" features={[
            'HTTP connections only',
            'Up to 3Gb traffic per day',
          ]} />
          <PlanCard name="individual" price="$10" features={[
            'Everything in basic',
            'TCP connections',
            'Up to 6Gb traffic per day',
          ]} />
          <PlanCard name="professional" price="$20" features={[
            'Everything in individual',
            'Up to 20Gb traffic per day',
          ]} />
        </div>
      </section>
    </div>
  )
}

function Feature({ title, desc }: { title: string, desc: string }) {
  return (
    <div className="bg-black/30 border border-white/10 rounded-xl p-6">
      <div className="text-accent font-semibold">{title}</div>
      <div className="text-white/70 mt-2 text-sm">{desc}</div>
    </div>
  )
}

function PlanCard({ name, price, features }: { name: string, price: string, features: string[] }) {
  return (
    <div className="bg-black/30 border border-white/10 rounded-xl p-6 flex flex-col">
      <div className="flex items-baseline gap-2">
        <div className="badge capitalize">{name}</div>
        <div className="text-2xl font-bold">{price}<span className="text-white/50 text-base">/mo</span></div>
      </div>
      <ul className="mt-4 text-sm text-white/80 space-y-2 list-disc list-inside">
        {features.map((f, i) => <li key={i}>{f}</li>)}
      </ul>
      <Link to="/app" className="btn mt-6">Choose plan</Link>
    </div>
  )
}
