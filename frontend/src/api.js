const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');

export function getApiBaseUrl() {
  return API_BASE_URL;
}

async function request(path, options = {}) {
  if (!API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL is not configured.');
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.headers || {}),
    },
  });

  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!response.ok) {
    const message = typeof data === 'object' && data?.message
      ? data.message
      : `Request failed with HTTP ${response.status}`;
    throw new Error(message);
  }

  return data;
}

export function listOrders() {
  return request('/orders');
}

export function getOrder(orderId) {
  return request(`/orders/${encodeURIComponent(orderId)}`);
}

export function createOrder(payload, idempotencyKey) {
  return request('/orders', {
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify(payload),
  });
}

export function getOpsMetrics() {
  return request('/ops/metrics');
}
