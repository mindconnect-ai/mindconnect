# The python image code_execute runs by default: the slim interpreter plus the
# office libraries and Mindconnect's own generators (mc_office), so a model
# writing a deck, a document or a workbook calls a function instead of pasting
# a generator into every request.
#
#   docker build -t ghcr.io/mindconnect-ai/code-exec-python agents/core/mc-agent-tools-code/images/python
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    MPLBACKEND=Agg \
    PYTHONPATH=/opt/mindconnect

COPY requirements.txt /tmp/requirements.txt
RUN pip install -r /tmp/requirements.txt && rm /tmp/requirements.txt

COPY mc_office /opt/mindconnect/mc_office
COPY smoke_test.py /opt/mindconnect/smoke_test.py

# An image whose generators or libraries do not load is never pushed.
RUN python /opt/mindconnect/smoke_test.py "$(mktemp -d)" --libraries

LABEL org.opencontainers.image.source="https://github.com/mindconnect-ai/mindconnect" \
      org.opencontainers.image.description="Python for Mindconnect's code_execute tool, with python-pptx, python-docx, openpyxl, matplotlib, pandas and the mc_office generators"
