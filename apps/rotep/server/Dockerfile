# SPDX-License-Identifier: GPL-3.0-only
# The relay on Cloud Run (docs/mcp-setup.md, "C. Cloud Run で動かす").
# Build it from a folder that holds this file, aitel_relay.py and aitel_mcp.py side by side.
FROM python:3.12-slim
WORKDIR /app
COPY aitel_relay.py aitel_mcp.py ./
# Cloud Run reaches the container from outside it. No .pyc: nothing here is ever written
ENV AITEL_RELAY_HOST=0.0.0.0 PYTHONDONTWRITEBYTECODE=1
USER nobody
# Cloud Run says in PORT where to listen. exec: the relay itself gets the signals, not a shell
CMD ["sh", "-c", "AITEL_RELAY_PORT=${PORT:-8080} exec python aitel_relay.py"]
