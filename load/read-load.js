// Read-path load test for the retrieval API (k6).
//
// Exercises the realistic fraud-ops query mix against GET /api/evaluations:
// list-by-customer, list-flagged with pagination, list-by-time-range, and the
// by-id detail view (ids harvested in setup). Reports p50/p95/p99 and req/s.
//
// Run via Docker (no host install) — see load/README.md:
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 \
//     -v "$PWD/load":/load grafana/k6 run /load/read-load.js
import http from "k6/http";
import { check, sleep } from "k6";
import { Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://host.docker.internal:8080";
const CUSTOMERS = parseInt(__ENV.CUSTOMERS || "2000", 10);

const errors = new Rate("errors");

export const options = {
  scenarios: {
    reads: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "15s", target: 25 }, // ramp up
        { duration: "45s", target: 25 }, // hold
        { duration: "10s", target: 0 },  // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500", "p(99)<1000"],
  },
};

// Harvest a handful of real eventIds for the detail endpoint.
export function setup() {
  const res = http.get(`${BASE_URL}/api/evaluations?size=50`);
  let ids = [];
  try {
    const body = JSON.parse(res.body);
    ids = (body.content || []).map((r) => r.eventId).filter(Boolean);
  } catch (_) {
    // leave ids empty — the VU loop simply skips the detail call
  }
  return { ids };
}

function customer() {
  const n = Math.floor(Math.random() * CUSTOMERS) + 1;
  return "cust-" + String(n).padStart(5, "0");
}

export default function (data) {
  const roll = Math.random();
  let res;

  if (roll < 0.55) {
    // list one customer's evaluations
    res = http.get(`${BASE_URL}/api/evaluations?customerId=${customer()}&size=20`, {
      tags: { ep: "list_by_customer" },
    });
  } else if (roll < 0.8) {
    // flagged decisions, paginated (fraud-ops triage view)
    const page = Math.floor(Math.random() * 5);
    res = http.get(`${BASE_URL}/api/evaluations?flagged=true&page=${page}&size=20`, {
      tags: { ep: "list_flagged" },
    });
  } else if (roll < 0.9 && data.ids.length > 0) {
    // by-id detail (full audit trail)
    const id = data.ids[Math.floor(Math.random() * data.ids.length)];
    res = http.get(`${BASE_URL}/api/evaluations/${id}`, { tags: { ep: "detail" } });
  } else {
    // last-24h window
    const from = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
    res = http.get(`${BASE_URL}/api/evaluations?from=${from}&size=20`, {
      tags: { ep: "list_by_time" },
    });
  }

  const ok = check(res, { "status 200": (r) => r.status === 200 });
  errors.add(!ok);
  sleep(0.1);
}
