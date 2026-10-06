import { useCallback, useEffect, useMemo, useState } from 'react';
import { createOrder, getApiBaseUrl, getOpsMetrics, getOrder, listOrders } from './api';
import './styles.css';

const ORDER_REFRESH_MS = 10000;
const OPS_REFRESH_MS = 15000;

const STATUS_META = {
  FULFILLED: { label: 'Fulfilled', tone: 'success' },
  PROCESSING: { label: 'Processing', tone: 'info' },
  CREATED: { label: 'Created', tone: 'warning' },
  FAILED: { label: 'Failed', tone: 'danger' },
  CANCELLED: { label: 'Cancelled', tone: 'muted' },
};

const PAYMENT_META = {
  COMPLETED: { label: 'Completed', tone: 'success' },
  PENDING: { label: 'Pending', tone: 'warning' },
  FAILED: { label: 'Failed', tone: 'danger' },
};

const INVENTORY_META = {
  RESERVED: { label: 'Reserved', tone: 'success' },
  PENDING: { label: 'Pending', tone: 'warning' },
  FAILED: { label: 'Failed', tone: 'danger' },
};

const QUEUE_META = {
  payment: 'Payment',
  inventory: 'Inventory',
  orderStatus: 'Order Status',
};

const LAMBDA_META = {
  orderApi: 'Order API',
  paymentWorker: 'Payment Worker',
  inventoryWorker: 'Inventory Worker',
  orderStatusWorker: 'Order Status Worker',
};

const RULE_META = {
  orderCreated: 'OrderCreated routing',
  orderStatus: 'Result-event routing',
};

function Badge({ value, map }) {
  const meta = map[value] || { label: value || 'Unknown', tone: 'muted' };
  return <span className={`badge badge-${meta.tone}`}>{meta.label}</span>;
}

function formatCurrency(amount, currency = 'INR') {
  try {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency,
      maximumFractionDigits: 2,
    }).format(Number(amount || 0));
  } catch {
    return `${currency} ${amount}`;
  }
}

function formatDate(value) {
  if (!value) return '—';

  const normalizedValue =
    typeof value === 'number' && value < 1_000_000_000_000
      ? value * 1000
      : value;

  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(normalizedValue));
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString('en-IN', { maximumFractionDigits: 1 });
}

function formatDuration(value) {
  const n = Number(value || 0);
  return n < 1000 ? `${n.toFixed(0)} ms` : `${(n / 1000).toFixed(2)} s`;
}

function formatAge(seconds) {
  const n = Number(seconds || 0);
  if (n < 60) return `${Math.round(n)}s`;
  if (n < 3600) return `${Math.round(n / 60)}m`;
  return `${(n / 3600).toFixed(1)}h`;
}

function severityTone({ visible = 0, dlqVisible = 0 }) {
  if (dlqVisible > 0 || visible >= 10) return 'danger';
  if (visible > 0) return 'warning';
  return 'success';
}

function EmptyState({ onRefresh }) {
  return (
    <div className="empty-state">
      <div className="empty-icon">⌁</div>
      <h3>No orders yet</h3>
      <p>Create an order from the dashboard and watch it move through Payment, Inventory, and Order Status.</p>
      <button className="btn btn-secondary" onClick={onRefresh}>Refresh</button>
    </div>
  );
}

function MetricCard({ label, value, hint, tone = 'default' }) {
  return (
    <div className={`metric-card metric-card-${tone}`}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      <div className="metric-hint">{hint}</div>
    </div>
  );
}

function CreateOrderPanel({ onCreated }) {
  const [customerId, setCustomerId] = useState('CUS-DASHBOARD-001');
  const [productId, setProductId] = useState('PROD-001');
  const [quantity, setQuantity] = useState(1);
  const [unitPrice, setUnitPrice] = useState('749.50');
  const [currency, setCurrency] = useState('INR');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  async function submit(event) {
    event.preventDefault();
    setError('');
    setSuccess('');
    setSubmitting(true);

    try {
      const idempotencyKey = `dashboard-${crypto.randomUUID()}`;
      const order = await createOrder({
        customerId: customerId.trim(),
        items: [{
          productId: productId.trim(),
          quantity: Number(quantity),
          unitPrice: Number(unitPrice),
        }],
        currency: currency.trim().toUpperCase(),
      }, idempotencyKey);

      setSuccess(`Created ${order.orderId}`);
      onCreated(order);
    } catch (err) {
      setError(err.message || 'Unable to create order.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="panel create-panel">
      <div className="panel-heading">
        <div>
          <div className="eyebrow">Command center</div>
          <h2>Create order</h2>
        </div>
        <span className="live-pill"><span className="live-dot" /> API connected</span>
      </div>

      <form className="create-form" onSubmit={submit}>
        <label>Customer ID<input value={customerId} onChange={(e) => setCustomerId(e.target.value)} required /></label>
        <label>Product ID<input value={productId} onChange={(e) => setProductId(e.target.value)} required /></label>
        <label>Quantity<input type="number" min="1" value={quantity} onChange={(e) => setQuantity(e.target.value)} required /></label>
        <label>Unit price<input type="number" min="0.01" step="0.01" value={unitPrice} onChange={(e) => setUnitPrice(e.target.value)} required /></label>
        <label>Currency<input value={currency} onChange={(e) => setCurrency(e.target.value)} maxLength="3" required /></label>
        <button className="btn btn-primary form-submit" type="submit" disabled={submitting}>
          {submitting ? 'Creating…' : 'Create order'}
        </button>
      </form>

      {success && <div className="notice notice-success">{success}</div>}
      {error && <div className="notice notice-danger">{error}</div>}
    </section>
  );
}

function HealthPill({ label, healthy, detail }) {
  return (
    <div className={`health-pill ${healthy ? 'health-ok' : 'health-bad'}`}>
      <span className="health-dot" />
      <div>
        <strong>{label}</strong>
        <span>{detail}</span>
      </div>
    </div>
  );
}

function QueueCard({ name, data }) {
  const tone = severityTone(data || {});
  const visible = Number(data?.visible || 0);
  const inFlight = Number(data?.inFlight || 0);
  const dlq = Number(data?.dlqVisible || 0);

  return (
    <div className={`ops-card queue-card queue-${tone}`}>
      <div className="ops-card-header">
        <div>
          <div className="eyebrow">SQS</div>
          <h3>{name}</h3>
        </div>
        <span className={`ops-state ops-state-${tone}`}>{tone === 'success' ? 'Healthy' : tone === 'warning' ? 'Backlog' : 'Attention'}</span>
      </div>
      <div className="queue-big-number">{formatNumber(visible)}</div>
      <div className="queue-big-label">visible messages</div>
      <div className="queue-stats">
        <div><span>In flight</span><strong>{formatNumber(inFlight)}</strong></div>
        <div><span>DLQ</span><strong className={dlq > 0 ? 'danger-text' : ''}>{formatNumber(dlq)}</strong></div>
        <div><span>Oldest</span><strong>{formatAge(data?.oldestAgeSeconds)}</strong></div>
      </div>
      <div className="queue-throughput">
        <span>10m sent {formatNumber(data?.sentLast10m)}</span>
        <span>received {formatNumber(data?.receivedLast10m)}</span>
        <span>deleted {formatNumber(data?.deletedLast10m)}</span>
      </div>
    </div>
  );
}

function OperationsPanel({ ops, loading, onRefresh }) {
  const queueValues = Object.values(ops?.queues || {});
  const visibleMessages = queueValues.reduce((sum, q) => sum + Number(q.visible || 0), 0);
  const dlqMessages = queueValues.reduce((sum, q) => sum + Number(q.dlqVisible || 0), 0);
  const oldestAge = queueValues.reduce((max, q) => Math.max(max, Number(q.oldestAgeSeconds || 0)), 0);
  const lambdaValues = Object.values(ops?.lambdas || {});
  const lambdaErrors = lambdaValues.reduce((sum, x) => sum + Number(x.errors || 0), 0);
  const eventBridgeValues = Object.values(ops?.eventBridge || {});
  const eventFailures = eventBridgeValues.reduce((sum, x) => sum + Number(x.failedInvocations || 0), 0);

  const queueHealthy = visibleMessages === 0 && dlqMessages === 0;
  const lambdaHealthy = lambdaErrors === 0;
  const eventHealthy = eventFailures === 0 && eventBridgeValues.every((x) => x.state === 'ENABLED');

  return (
    <section className="panel operations-panel">
      <div className="panel-heading">
        <div>
          <div className="eyebrow">AWS operational state</div>
          <h2>Event flow health</h2>
        </div>
        <div className="ops-actions">
          <div className="last-updated">{ops?.generatedAt ? `CloudWatch window · ${ops.windowMinutes}m · ${formatDate(ops.generatedAt)}` : 'Loading telemetry…'}</div>
          <button className="btn btn-secondary" onClick={onRefresh} disabled={loading}>{loading ? 'Loading…' : 'Refresh ops'}</button>
        </div>
      </div>

      <div className="health-row">
        <HealthPill label="SQS" healthy={queueHealthy} detail={queueHealthy ? 'No backlog / DLQ messages' : `${formatNumber(visibleMessages)} backlog · ${formatNumber(dlqMessages)} DLQ`} />
        <HealthPill label="Lambda" healthy={lambdaHealthy} detail={lambdaHealthy ? '0 errors in window' : `${formatNumber(lambdaErrors)} errors in window`} />
        <HealthPill label="EventBridge" healthy={eventHealthy} detail={eventHealthy ? 'Rules enabled · 0 failed invocations' : `${formatNumber(eventFailures)} failed invocations or disabled rule`} />
      </div>

      {ops?.error && <div className="notice notice-danger">Operations telemetry error: {ops.error}</div>}

      <div className="ops-overview-grid">
        <MetricCard label="Open queue messages" value={formatNumber(visibleMessages)} hint="Current visible backlog" tone={visibleMessages ? 'warning' : 'success'} />
        <MetricCard label="DLQ messages" value={formatNumber(dlqMessages)} hint="Payment + inventory + status DLQs" tone={dlqMessages ? 'danger' : 'success'} />
        <MetricCard label="Lambda errors" value={formatNumber(lambdaErrors)} hint="Last 10 minutes" tone={lambdaErrors ? 'danger' : 'success'} />
        <MetricCard label="EventBridge failures" value={formatNumber(eventFailures)} hint="Failed rule invocations" tone={eventFailures ? 'danger' : 'success'} />
        <MetricCard label="Oldest message" value={formatAge(oldestAge)} hint="Across processing queues" tone={oldestAge > 60 ? 'warning' : 'success'} />
      </div>

      <div className="ops-section">
        <div className="ops-section-title">Queue health</div>
        <div className="queue-grid">
          {Object.entries(ops?.queues || {}).map(([key, data]) => (
            <QueueCard key={key} name={QUEUE_META[key] || key} data={data} />
          ))}
        </div>
      </div>

      <div className="ops-section">
        <div className="ops-section-title">Lambda health · CloudWatch</div>
        <div className="ops-table-wrap">
          <table className="ops-table">
            <thead><tr><th>Worker</th><th>Invocations</th><th>Errors</th><th>Error rate</th><th>Avg duration</th></tr></thead>
            <tbody>
              {Object.entries(ops?.lambdas || {}).map(([key, data]) => (
                <tr key={key}>
                  <td><strong>{LAMBDA_META[key] || key}</strong><div className="subtext">{data.functionName}</div></td>
                  <td>{formatNumber(data.invocations)}</td>
                  <td className={Number(data.errors || 0) > 0 ? 'danger-text' : ''}>{formatNumber(data.errors)}</td>
                  <td><Badge value={Number(data.errorRatePercent || 0) > 0 ? 'FAILED' : 'COMPLETED'} map={{ COMPLETED: { label: `${Number(data.errorRatePercent || 0).toFixed(2)}%`, tone: 'success' }, FAILED: { label: `${Number(data.errorRatePercent || 0).toFixed(2)}%`, tone: 'danger' } }} /></td>
                  <td>{formatDuration(data.avgDurationMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="ops-section">
        <div className="ops-section-title">EventBridge rules · CloudWatch</div>
        <div className="ops-table-wrap">
          <table className="ops-table">
            <thead><tr><th>Rule</th><th>State</th><th>Targets</th><th>Matched</th><th>Invocations</th><th>Failed</th></tr></thead>
            <tbody>
              {Object.entries(ops?.eventBridge || {}).map(([key, data]) => (
                <tr key={key}>
                  <td><strong>{RULE_META[key] || key}</strong><div className="subtext">{data.ruleName}</div></td>
                  <td><Badge value={data.state} map={{ ENABLED: { label: 'Enabled', tone: 'success' }, DISABLED: { label: 'Disabled', tone: 'danger' } }} /></td>
                  <td>{data.targetCount}</td>
                  <td>{formatNumber(data.matchedEvents)}</td>
                  <td>{formatNumber(data.invocations)}</td>
                  <td className={Number(data.failedInvocations || 0) > 0 ? 'danger-text' : ''}>{formatNumber(data.failedInvocations)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );
}

function OrderDrawer({ order, loading, onClose, onRefresh }) {
  if (!order) return null;

  return (
    <div className="drawer-backdrop" onMouseDown={onClose}>
      <aside className="drawer" onMouseDown={(event) => event.stopPropagation()}>
        <div className="drawer-header">
          <div><div className="eyebrow">Order detail</div><h2>{order.orderId}</h2></div>
          <button className="icon-btn" onClick={onClose} aria-label="Close">×</button>
        </div>

        <div className="drawer-status"><Badge value={order.status} map={STATUS_META} /><span>{formatDate(order.updatedAt)}</span></div>

        <div className="detail-grid">
          <div className="detail-card"><span>Payment</span><strong><Badge value={order.paymentStatus} map={PAYMENT_META} /></strong></div>
          <div className="detail-card"><span>Inventory</span><strong><Badge value={order.inventoryStatus} map={INVENTORY_META} /></strong></div>
          <div className="detail-card"><span>Total</span><strong>{formatCurrency(order.totalAmount, order.currency)}</strong></div>
          <div className="detail-card"><span>Customer</span><strong>{order.customerId}</strong></div>
        </div>

        <div className="timeline">
          <div className="timeline-title">Processing flow</div>
          {[
            ['Order created', true],
            ['Payment', order.paymentStatus === 'COMPLETED' || order.paymentStatus === 'FAILED'],
            ['Inventory', order.inventoryStatus === 'RESERVED' || order.inventoryStatus === 'FAILED'],
            ['Order status', order.status === 'FULFILLED' || order.status === 'FAILED' || order.status === 'CANCELLED'],
          ].map(([label, done]) => (
            <div className={`timeline-row ${done ? 'timeline-done' : ''}`} key={label}><span className="timeline-dot" /><span>{label}</span></div>
          ))}
        </div>

        <div className="items-section">
          <div className="timeline-title">Items</div>
          {order.items?.map((item, index) => (
            <div className="item-row" key={`${item.productId}-${index}`}>
              <div><strong>{item.productId}</strong><span>Qty {item.quantity}</span></div>
              <strong>{formatCurrency(item.unitPrice, order.currency)}</strong>
            </div>
          ))}
        </div>

        <button className="btn btn-secondary full-width" onClick={onRefresh} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh order'}</button>
      </aside>
    </div>
  );
}

export default function App() {
  const [orders, setOrders] = useState([]);
  const [selectedOrder, setSelectedOrder] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [lastUpdated, setLastUpdated] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [ops, setOps] = useState(null);
  const [opsLoading, setOpsLoading] = useState(true);
  const [opsError, setOpsError] = useState('');

  const refreshOrders = useCallback(async () => {
    setError('');
    setRefreshing(true);
    try {
      const data = await listOrders();
      const sorted = [...(Array.isArray(data) ? data : [])].sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt));
      setOrders(sorted);
      setLastUpdated(new Date());
    } catch (err) {
      setError(err.message || 'Unable to load orders.');
    } finally {
      setRefreshing(false);
      setLoading(false);
    }
  }, []);

  const refreshOps = useCallback(async () => {
    setOpsError('');
    setOpsLoading(true);
    try {
      const data = await getOpsMetrics();
      setOps(data);
    } catch (err) {
      setOpsError(err.message || 'Unable to load operational metrics.');
    } finally {
      setOpsLoading(false);
    }
  }, []);

  useEffect(() => {
    refreshOrders();
    const timer = setInterval(refreshOrders, ORDER_REFRESH_MS);
    return () => clearInterval(timer);
  }, [refreshOrders]);

  useEffect(() => {
    refreshOps();
    const timer = setInterval(refreshOps, OPS_REFRESH_MS);
    return () => clearInterval(timer);
  }, [refreshOps]);

  const metrics = useMemo(() => {
    const fulfilled = orders.filter((order) => order.status === 'FULFILLED').length;
    const failed = orders.filter((order) => order.status === 'FAILED').length;
    const processing = orders.filter((order) => order.status === 'PROCESSING' || order.status === 'CREATED').length;
    const revenue = orders.filter((order) => order.status === 'FULFILLED').reduce((sum, order) => sum + Number(order.totalAmount || 0), 0);
    return { total: orders.length, fulfilled, failed, processing, revenue };
  }, [orders]);

  const opsRollup = useMemo(() => {
    const queues = Object.values(ops?.queues || {});
    const lambdas = Object.values(ops?.lambdas || {});
    const rules = Object.values(ops?.eventBridge || {});
    return {
      visible: queues.reduce((sum, q) => sum + Number(q.visible || 0), 0),
      dlq: queues.reduce((sum, q) => sum + Number(q.dlqVisible || 0), 0),
      errors: lambdas.reduce((sum, x) => sum + Number(x.errors || 0), 0),
      failedInvocations: rules.reduce((sum, x) => sum + Number(x.failedInvocations || 0), 0),
    };
  }, [ops]);

  async function openOrder(order) {
    setSelectedOrder(order);
    setDetailLoading(true);
    try {
      const fresh = await getOrder(order.orderId);
      setSelectedOrder(fresh);
    } catch (err) {
      setError(err.message || 'Unable to load order.');
    } finally {
      setDetailLoading(false);
    }
  }

  async function refreshSelected() {
    if (!selectedOrder) return;
    setDetailLoading(true);
    try {
      const fresh = await getOrder(selectedOrder.orderId);
      setSelectedOrder(fresh);
      await refreshOrders();
    } finally {
      setDetailLoading(false);
    }
  }

  function handleCreated(order) {
    setOrders((current) => [order, ...current.filter((item) => item.orderId !== order.orderId)]);
    setSelectedOrder(order);
    refreshOps();
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-block">
          <div className="brand-mark">C</div>
          <div><div className="brand-name">CloudScale</div><div className="brand-subtitle">Order processing control plane</div></div>
        </div>
        <div className="topbar-right">
          <div className="endpoint-pill" title={getApiBaseUrl()}><span className="status-dot" /><span>ap-south-1</span></div>
          <button className="btn btn-secondary" onClick={async () => { await Promise.all([refreshOrders(), refreshOps()]); }} disabled={refreshing || opsLoading}>
            {refreshing || opsLoading ? 'Refreshing…' : 'Refresh all'}
          </button>
        </div>
      </header>

      <main className="page">
        <section className="hero">
          <div>
            <div className="eyebrow">Operations dashboard</div>
            <h1>Orders, events, workers — one view.</h1>
            <p>Track order state and the AWS event pipeline with live SQS backlog, Lambda health, EventBridge routing, and CloudWatch telemetry.</p>
          </div>
          <div className="hero-meta"><div className="hero-chip">Orders · 10s</div><div className="hero-chip">Ops · 15s</div><div className="hero-chip">API · HTTP</div></div>
        </section>

        {(error || opsError) && <div className="notice notice-danger global-notice">{error || opsError}</div>}

        <section className="metrics-grid">
          <MetricCard label="Total orders" value={metrics.total} hint="Across the Order API" />
          <MetricCard label="Fulfilled" value={metrics.fulfilled} hint="Payment + inventory completed" />
          <MetricCard label="Processing" value={metrics.processing} hint="Created or in-flight" />
          <MetricCard label="Failed" value={metrics.failed} hint="Orders requiring attention" />
          <MetricCard label="Fulfilled value" value={formatCurrency(metrics.revenue)} hint="Current dashboard total" />
        </section>

        <OperationsPanel ops={ops ? { ...ops, error: opsError } : { error: opsError }} loading={opsLoading} onRefresh={refreshOps} />

        <section className="ops-summary-strip">
          <div><span>Open queues</span><strong>{formatNumber(opsRollup.visible)}</strong></div>
          <div><span>DLQ</span><strong className={opsRollup.dlq ? 'danger-text' : ''}>{formatNumber(opsRollup.dlq)}</strong></div>
          <div><span>Lambda errors · 10m</span><strong className={opsRollup.errors ? 'danger-text' : ''}>{formatNumber(opsRollup.errors)}</strong></div>
          <div><span>Event failures · 10m</span><strong className={opsRollup.failedInvocations ? 'danger-text' : ''}>{formatNumber(opsRollup.failedInvocations)}</strong></div>
        </section>

        <CreateOrderPanel onCreated={handleCreated} />

        <section className="panel orders-panel">
          <div className="panel-heading">
            <div><div className="eyebrow">Live orders</div><h2>Recent activity</h2></div>
            <div className="last-updated">{lastUpdated ? `Updated ${formatDate(lastUpdated)}` : 'Loading…'}</div>
          </div>

          {loading ? (
            <div className="loading-state"><div className="spinner" />Loading orders…</div>
          ) : orders.length === 0 ? (
            <EmptyState onRefresh={refreshOrders} />
          ) : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Order</th><th>Customer</th><th>Total</th><th>Status</th><th>Payment</th><th>Inventory</th><th>Updated</th></tr></thead>
                <tbody>
                  {orders.map((order) => (
                    <tr key={order.orderId} onClick={() => openOrder(order)} className="clickable-row">
                      <td><div className="order-cell"><strong>{order.orderId}</strong><span>{order.items?.length || 0} item{(order.items?.length || 0) === 1 ? '' : 's'}</span></div></td>
                      <td>{order.customerId}</td>
                      <td>{formatCurrency(order.totalAmount, order.currency)}</td>
                      <td><Badge value={order.status} map={STATUS_META} /></td>
                      <td><Badge value={order.paymentStatus} map={PAYMENT_META} /></td>
                      <td><Badge value={order.inventoryStatus} map={INVENTORY_META} /></td>
                      <td>{formatDate(order.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </main>

      <footer className="footer">
        <span>CloudScale · AWS serverless order processing</span>
        <span>Backend API: {getApiBaseUrl() || 'not configured'}</span>
      </footer>

      <OrderDrawer order={selectedOrder} loading={detailLoading} onClose={() => setSelectedOrder(null)} onRefresh={refreshSelected} />
    </div>
  );
}
