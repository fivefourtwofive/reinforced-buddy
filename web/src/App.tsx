import { Link, Outlet, Route, Routes } from 'react-router-dom'
import Landing from './pages/Landing'
import Installation from './pages/Installation'
import Dashboard from './pages/Dashboard'

export default function App() {
  return (
    <div className="min-h-full flex flex-col">
      <header className="border-b border-white/10 bg-primary/60 backdrop-blur sticky top-0 z-10">
        <div className="container flex items-center justify-between py-3">
          <Link to="/" className="flex items-center gap-2 text-xl font-bold">
            <span className="w-2.5 h-2.5 rounded-full bg-accent inline-block"></span>
            Port Buddy
          </Link>
          <nav className="flex items-center gap-6 text-sm">
            <Link to="/install">Installation</Link>
            <Link to="/app" className="btn">App</Link>
          </nav>
        </div>
      </header>

      <main className="flex-1">
        <Routes>
          <Route path="/" element={<Landing/>} />
          <Route path="/install" element={<Installation/>} />
          <Route path="/app" element={<Dashboard/>} />
        </Routes>
        <Outlet />
      </main>

      <footer className="border-t border-white/10 py-8 mt-16">
        <div className="container text-sm text-white/60 flex items-center justify-between">
          <span>© {new Date().getFullYear()} Port Buddy</span>
          <div className="flex gap-4">
            <a href="#pricing">Pricing</a>
            <a href="#use-cases">Use Cases</a>
            <a href="#docs">Docs</a>
          </div>
        </div>
      </footer>
    </div>
  )
}
