import { useState, useEffect, useCallback } from 'react';
import {
  Play,
  Square,
  RefreshCw,
  Activity,
  Users,
  Clock,
  BarChart3,
  TrendingUp,
  Server,
  AlertCircle,
  CheckCircle2,
  Zap,
  Plus,
  Trash2,
  X
} from 'lucide-react';

interface Scenario {
  scenarioId: string;
  name: string;
  url: string;
  method: string;
  targetRps: number;
  maxConcurrency: number;
  durationSeconds: number;
  loadProfileType: string;
  status: string;
  createdTimestamp: number;
  assignedWorkers: string[];
}

interface Worker {
  workerId: string;
  hostname: string;
  status: string;
  availableCores: number;
  activeScenarios: number;
  lastHeartbeat: string;
}

interface ScenarioStatus {
  scenarioId: string;
  status: string;
  currentRps: number;
  totalRequests: number;
  successfulRequests: number;
  failedRequests: number;
  latencyP50Ms: number;
  latencyP95Ms: number;
  latencyP99Ms: number;
  elapsedTimeMs: number;
  remainingTimeMs: number;
  activeWorkers: number;
}

const API_BASE = '/api/v1';

function App() {
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [workers, setWorkers] = useState<Worker[]>([]);
  const [activeStatus, setActiveStatus] = useState<ScenarioStatus | null>(null);
  const [selectedScenario, setSelectedScenario] = useState<string | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [formData, setFormData] = useState({
    name: '',
    url: '',
    method: 'GET',
    targetRps: 10000,
    durationSeconds: 60,
    maxConcurrency: 1000,
    loadProfileType: 'constant',
    rampUpSeconds: 10,
    rampDownSeconds: 5,
    synchronizedStart: false,
  });

  const fetchScenarios = useCallback(async () => {
    try {
      const response = await fetch(`${API_BASE}/scenarios`);
      if (response.ok) {
        const data = await response.json();
        setScenarios(data);
      }
    } catch (e) {
      console.error('Failed to fetch scenarios', e);
    }
  }, []);

  const fetchWorkers = useCallback(async () => {
    try {
      const response = await fetch(`${API_BASE}/scenarios/workers`);
      if (response.ok) {
        const data = await response.json();
        setWorkers(data);
      }
    } catch (e) {
      console.error('Failed to fetch workers', e);
    }
  }, []);

  const fetchStatus = useCallback(async (scenarioId: string) => {
    try {
      const response = await fetch(`${API_BASE}/scenarios/${scenarioId}/status`);
      if (response.ok) {
        const data = await response.json();
        setActiveStatus(data);
      }
    } catch (e) {
      console.error('Failed to fetch status', e);
    }
  }, []);

  useEffect(() => {
    fetchScenarios();
    fetchWorkers();
    const interval = setInterval(() => {
      fetchScenarios();
      fetchWorkers();
      if (selectedScenario) {
        fetchStatus(selectedScenario);
      }
    }, 2000);
    return () => clearInterval(interval);
  }, [fetchScenarios, fetchWorkers, fetchStatus, selectedScenario]);

  const createScenario = async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(`${API_BASE}/scenarios`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(formData),
      });
      if (!response.ok) {
        throw new Error('Failed to create scenario');
      }
      setShowCreateModal(false);
      fetchScenarios();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unknown error');
    } finally {
      setLoading(false);
    }
  };

  const startTest = async (scenarioId: string) => {
    setLoading(true);
    try {
      await fetch(`${API_BASE}/scenarios/${scenarioId}/start`, { method: 'POST' });
      setSelectedScenario(scenarioId);
      fetchStatus(scenarioId);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to start test');
    } finally {
      setLoading(false);
    }
  };

  const stopTest = async (scenarioId: string) => {
    setLoading(true);
    try {
      await fetch(`${API_BASE}/scenarios/${scenarioId}/stop?immediate=false`, { method: 'POST' });
      setActiveStatus(null);
      setSelectedScenario(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to stop test');
    } finally {
      setLoading(false);
    }
  };

  const deleteScenario = async (scenarioId: string) => {
    try {
      await fetch(`${API_BASE}/scenarios/${scenarioId}`, { method: 'DELETE' });
      fetchScenarios();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete scenario');
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'RUNNING': return 'text-white font-medium';
      case 'QUEUED': return 'text-neutral-400';
      case 'COMPLETED': return 'text-neutral-300';
      case 'FAILED':
      case 'CANCELLED': return 'text-neutral-500';
      default: return 'text-neutral-500';
    }
  };

  return (
    <div className="min-h-screen bg-black text-white">
      {/* Header */}
      <header className="border-b border-neutral-800 bg-black sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-4">
            <div className="p-2 bg-white rounded">
              <Zap className="w-5 h-5 text-black" />
            </div>
            <div>
              <h1 className="text-lg font-bold tracking-tight">Load Test Platform</h1>
              <p className="text-xs text-neutral-600">Distributed Performance Testing</p>
            </div>
          </div>
          <div className="flex items-center gap-6">
            <div className="flex items-center gap-2 text-sm text-neutral-400">
              <div className={`w-2 h-2 rounded-full ${workers.length > 0 ? 'bg-white' : 'bg-neutral-700'}`} />
              <span>{workers.length} Workers</span>
            </div>
            <button
              onClick={() => setShowCreateModal(true)}
              className="flex items-center gap-2 px-4 py-2 bg-white text-black hover:bg-neutral-200 rounded font-medium transition-colors"
            >
              <Plus className="w-4 h-4" />
              New Test
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-6 py-8">
        {error && (
          <div className="mb-6 p-4 border border-neutral-800 rounded flex items-center gap-3">
            <AlertCircle className="w-5 h-5 text-neutral-400" />
            <span className="text-neutral-400">{error}</span>
            <button onClick={() => setError(null)} className="ml-auto text-neutral-600 hover:text-white">
              <X className="w-4 h-4" />
            </button>
          </div>
        )}

        {/* Stats Grid */}
        {activeStatus && (
          <div className="mb-8 grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-px bg-neutral-800 rounded overflow-hidden">
            <div className="bg-black p-4">
              <div className="flex items-center gap-2 text-neutral-600 text-xs mb-2">
                <TrendingUp className="w-3 h-3" />
                Current RPS
              </div>
              <div className="text-2xl font-bold">
                {activeStatus.currentRps.toLocaleString()}
              </div>
            </div>
            <div className="bg-black p-4">
              <div className="flex items-center gap-2 text-neutral-600 text-xs mb-2">
                <Activity className="w-3 h-3" />
                Total Requests
              </div>
              <div className="text-2xl font-bold">
                {activeStatus.totalRequests.toLocaleString()}
              </div>
            </div>
            <div className="bg-black p-4">
              <div className="flex items-center gap-2 text-neutral-600 text-xs mb-2">
                <CheckCircle2 className="w-3 h-3" />
                Success Rate
              </div>
              <div className="text-2xl font-bold">
                {activeStatus.totalRequests > 0
                  ? ((activeStatus.successfulRequests / activeStatus.totalRequests) * 100).toFixed(1)
                  : 0}%
              </div>
            </div>
            <div className="bg-black p-4">
              <div className="flex items-center gap-2 text-neutral-600 text-xs mb-2">
                <Clock className="w-3 h-3" />
                Latency P50
              </div>
              <div className="text-2xl font-bold">
                {activeStatus.latencyP50Ms.toFixed(0)}
                <span className="text-sm text-neutral-600 ml-1">ms</span>
              </div>
            </div>
            <div className="bg-black p-4">
              <div className="flex items-center gap-2 text-neutral-600 text-xs mb-2">
                <BarChart3 className="w-3 h-3" />
                Latency P99
              </div>
              <div className="text-2xl font-bold">
                {activeStatus.latencyP99Ms.toFixed(0)}
                <span className="text-sm text-neutral-600 ml-1">ms</span>
              </div>
            </div>
            <div className="bg-black p-4">
              <div className="flex items-center gap-2 text-neutral-600 text-xs mb-2">
                <Users className="w-3 h-3" />
                Active Workers
              </div>
              <div className="text-2xl font-bold">
                {activeStatus.activeWorkers}
              </div>
            </div>
          </div>
        )}

        {/* Main Content */}
        <div className="grid lg:grid-cols-4 gap-px bg-neutral-800 rounded overflow-hidden">
          {/* Scenarios List */}
          <div className="lg:col-span-3 bg-black">
            <div className="px-5 py-4 border-b border-neutral-800 flex items-center justify-between">
              <h2 className="font-medium flex items-center gap-2">
                <Server className="w-4 h-4 text-neutral-600" />
                Test Scenarios
              </h2>
              <button
                onClick={fetchScenarios}
                className="p-1.5 hover:bg-neutral-900 rounded text-neutral-600 hover:text-white"
              >
                <RefreshCw className="w-4 h-4" />
              </button>
            </div>

            {scenarios.length === 0 ? (
              <div className="p-12 text-center text-neutral-600">
                <Server className="w-10 h-10 mx-auto mb-3 opacity-30" />
                <p>No scenarios yet</p>
                <p className="text-xs mt-1">Create your first test to begin</p>
              </div>
            ) : (
              <div className="divide-y divide-neutral-800">
                {scenarios.map((scenario) => (
                  <div
                    key={scenario.scenarioId}
                    className={`px-5 py-4 hover:bg-neutral-900/50 cursor-pointer transition-colors ${
                      selectedScenario === scenario.scenarioId ? 'bg-neutral-900' : ''
                    }`}
                    onClick={() => {
                      setSelectedScenario(scenario.scenarioId);
                      if (scenario.status === 'RUNNING') {
                        fetchStatus(scenario.scenarioId);
                      }
                    }}
                  >
                    <div className="flex items-start justify-between">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-3 mb-1">
                          <span className={`flex items-center gap-2 ${getStatusColor(scenario.status)}`}>
                            {scenario.status === 'RUNNING' && <Activity className="w-3 h-3 animate-pulse" />}
                            {scenario.status === 'QUEUED' && <Clock className="w-3 h-3" />}
                            {scenario.status === 'COMPLETED' && <CheckCircle2 className="w-3 h-3" />}
                            {(scenario.status === 'FAILED' || scenario.status === 'CANCELLED') && <AlertCircle className="w-3 h-3" />}
                            <span className="font-medium">{scenario.name}</span>
                          </span>
                          <span className="px-2 py-0.5 text-xs border border-neutral-700 text-neutral-400 rounded">
                            {scenario.method}
                          </span>
                          <span className="px-2 py-0.5 text-xs border border-neutral-700 text-neutral-500 rounded">
                            {scenario.loadProfileType}
                          </span>
                        </div>
                        <div className="text-sm text-neutral-600 truncate font-mono">{scenario.url}</div>
                        <div className="flex items-center gap-6 mt-2 text-xs text-neutral-600">
                          <span>{scenario.targetRps.toLocaleString()} RPS</span>
                          <span>{scenario.durationSeconds}s</span>
                          <span>{scenario.maxConcurrency} concurrency</span>
                        </div>
                      </div>
                      <div className="flex items-center gap-2 ml-4">
                        {scenario.status === 'QUEUED' && (
                          <button
                            onClick={(e) => { e.stopPropagation(); startTest(scenario.scenarioId); }}
                            disabled={loading}
                            className="p-2 border border-neutral-700 hover:border-white hover:text-white text-neutral-400 rounded transition-colors"
                          >
                            <Play className="w-4 h-4" />
                          </button>
                        )}
                        {scenario.status === 'RUNNING' && (
                          <button
                            onClick={(e) => { e.stopPropagation(); stopTest(scenario.scenarioId); }}
                            disabled={loading}
                            className="p-2 border border-neutral-700 hover:border-neutral-500 rounded transition-colors"
                          >
                            <Square className="w-4 h-4" />
                          </button>
                        )}
                        {scenario.status !== 'RUNNING' && (
                          <button
                            onClick={(e) => { e.stopPropagation(); deleteScenario(scenario.scenarioId); }}
                            className="p-2 text-neutral-700 hover:text-neutral-400 transition-colors"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Workers Panel */}
          <div className="bg-black border-l border-neutral-800">
            <div className="px-5 py-4 border-b border-neutral-800">
              <h2 className="font-medium flex items-center gap-2">
                <Users className="w-4 h-4 text-neutral-600" />
                Workers
              </h2>
            </div>

            {workers.length === 0 ? (
              <div className="p-8 text-center text-neutral-600">
                <Users className="w-8 h-8 mx-auto mb-2 opacity-30" />
                <p className="text-sm">No workers</p>
              </div>
            ) : (
              <div className="divide-y divide-neutral-800">
                {workers.map((worker) => (
                  <div key={worker.workerId} className="px-5 py-4">
                    <div className="flex items-center justify-between mb-2">
                      <span className="font-mono text-sm text-neutral-400">
                        {worker.workerId.slice(0, 8)}
                      </span>
                      <span className={`text-xs ${
                        worker.status === 'IDLE' ? 'text-neutral-500' :
                        worker.status === 'RUNNING' ? 'text-white' : 'text-neutral-600'
                      }`}>
                        {worker.status}
                      </span>
                    </div>
                    <div className="text-xs text-neutral-700 space-y-1">
                      <div>{worker.hostname}</div>
                      <div>{worker.availableCores} cores</div>
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* Platform Stats */}
            <div className="px-5 py-4 border-t border-neutral-800">
              <h3 className="text-xs text-neutral-600 mb-3">Statistics</h3>
              <div className="space-y-2 text-sm">
                <div className="flex justify-between text-neutral-500">
                  <span>Total Scenarios</span>
                  <span className="font-mono">{scenarios.length}</span>
                </div>
                <div className="flex justify-between text-neutral-500">
                  <span>Active Tests</span>
                  <span className="font-mono">{scenarios.filter(s => s.status === 'RUNNING').length}</span>
                </div>
                <div className="flex justify-between text-neutral-500">
                  <span>Available Workers</span>
                  <span className="font-mono">{workers.filter(w => w.status === 'IDLE').length}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </main>

      {/* Create Modal */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-black/80 flex items-center justify-center z-50 p-4">
          <div className="bg-black border border-neutral-800 rounded w-full max-w-lg max-h-[90vh] overflow-y-auto">
            <div className="px-6 py-4 border-b border-neutral-800 flex items-center justify-between">
              <h2 className="font-medium">Create Test Scenario</h2>
              <button onClick={() => setShowCreateModal(false)} className="text-neutral-600 hover:text-white">
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="p-6 space-y-4">
              <div>
                <label className="block text-xs text-neutral-600 mb-2">Scenario Name</label>
                <input
                  type="text"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  className="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 rounded focus:outline-none focus:border-neutral-600 text-white"
                  placeholder="API Load Test"
                />
              </div>

              <div>
                <label className="block text-xs text-neutral-600 mb-2">Target URL</label>
                <input
                  type="text"
                  value={formData.url}
                  onChange={(e) => setFormData({ ...formData, url: e.target.value })}
                  className="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 rounded focus:outline-none focus:border-neutral-600 font-mono text-sm"
                  placeholder="https://api.example.com/endpoint"
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs text-neutral-600 mb-2">HTTP Method</label>
                  <select
                    value={formData.method}
                    onChange={(e) => setFormData({ ...formData, method: e.target.value })}
                    className="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 rounded focus:outline-none focus:border-neutral-600"
                  >
                    <option value="GET">GET</option>
                    <option value="POST">POST</option>
                    <option value="PUT">PUT</option>
                    <option value="DELETE">DELETE</option>
                    <option value="PATCH">PATCH</option>
                  </select>
                </div>

                <div>
                  <label className="block text-xs text-neutral-600 mb-2">Load Profile</label>
                  <select
                    value={formData.loadProfileType}
                    onChange={(e) => setFormData({ ...formData, loadProfileType: e.target.value })}
                    className="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 rounded focus:outline-none focus:border-neutral-600"
                  >
                    <option value="constant">Constant</option>
                    <option value="rampup">Ramp Up</option>
                    <option value="spike">Spike</option>
                    <option value="stepup">Step Up</option>
                  </select>
                </div>
              </div>

              <div className="grid grid-cols-3 gap-4">
                <div>
                  <label className="block text-xs text-neutral-600 mb-2">Target RPS</label>
                  <input
                    type="number"
                    value={formData.targetRps}
                    onChange={(e) => setFormData({ ...formData, targetRps: parseInt(e.target.value) || 0 })}
                    className="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 rounded focus:outline-none focus:border-neutral-600"
                  />
                </div>

                <div>
                  <label className="block text-xs text-neutral-600 mb-2">Duration (s)</label>
                  <input
                    type="number"
                    value={formData.durationSeconds}
                    onChange={(e) => setFormData({ ...formData, durationSeconds: parseInt(e.target.value) || 0 })}
                    className="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 rounded focus:outline-none focus:border-neutral-600"
                  />
                </div>

                <div>
                  <label className="block text-xs text-neutral-600 mb-2">Concurrency</label>
                  <input
                    type="number"
                    value={formData.maxConcurrency}
                    onChange={(e) => setFormData({ ...formData, maxConcurrency: parseInt(e.target.value) || 0 })}
                    className="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 rounded focus:outline-none focus:border-neutral-600"
                  />
                </div>
              </div>

              {formData.loadProfileType === 'rampup' && (
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-xs text-neutral-600 mb-2">Ramp Up (s)</label>
                    <input
                      type="number"
                      value={formData.rampUpSeconds}
                      onChange={(e) => setFormData({ ...formData, rampUpSeconds: parseInt(e.target.value) || 0 })}
                      className="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 rounded focus:outline-none focus:border-neutral-600"
                    />
                  </div>
                  <div>
                    <label className="block text-xs text-neutral-600 mb-2">Ramp Down (s)</label>
                    <input
                      type="number"
                      value={formData.rampDownSeconds}
                      onChange={(e) => setFormData({ ...formData, rampDownSeconds: parseInt(e.target.value) || 0 })}
                      className="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 rounded focus:outline-none focus:border-neutral-600"
                    />
                  </div>
                </div>
              )}

              {formData.loadProfileType === 'spike' && (
                <label className="flex items-center gap-3 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={formData.synchronizedStart}
                    onChange={(e) => setFormData({ ...formData, synchronizedStart: e.target.checked })}
                    className="w-4 h-4 bg-neutral-900 border border-neutral-700 rounded"
                  />
                  <span className="text-sm text-neutral-400">Synchronized Burst (Phaser)</span>
                </label>
              )}
            </div>

            <div className="px-6 py-4 border-t border-neutral-800 flex justify-end gap-3">
              <button
                onClick={() => setShowCreateModal(false)}
                className="px-4 py-2 text-neutral-400 hover:text-white"
              >
                Cancel
              </button>
              <button
                onClick={createScenario}
                disabled={loading || !formData.name || !formData.url}
                className="px-4 py-2 bg-white text-black hover:bg-neutral-200 rounded font-medium disabled:opacity-30 disabled:cursor-not-allowed flex items-center gap-2"
              >
                {loading ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />}
                Create
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
