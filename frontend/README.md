# CloudScale Frontend

The CloudScale frontend is a React + Vite operations dashboard for the AWS order-processing platform.

## Features

- View orders and current processing state.
- Create test orders.
- Inspect payment and inventory status.
- View the order-processing timeline.
- View SQS, DLQ, Lambda, and EventBridge operational telemetry.

## Setup

Create a local environment file:

```bash
cp .env.example .env
```

Set:

```env
VITE_API_BASE_URL=https://97ptssr9kf.execute-api.ap-south-1.amazonaws.com
```

Install dependencies:

```bash
npm install
```

Start the development server:

```bash
npm run dev
```

Create a production build:

```bash
npm run build
```

Run linting:

```bash
npm run lint
```

The frontend can be deployed to Vercel with the `frontend/` directory as the project root and `VITE_API_BASE_URL` configured as an environment variable.
