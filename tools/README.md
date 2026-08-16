# Development Tools

- `materials/generate-pdfs.py` creates local PDF handouts under the ignored
  `private/pdfs/` directory. It is not a production upload path.
- `legacy/ppt_reader.py` is an archived Streamlit extraction prototype. It is
  not part of the Structify web application or production deployment.

Production PPT ingestion uses the reviewed offline manifest workflow described
in `PRODUCTION_DEPLOYMENT_GUIDE.md`; private PPT files and generated images stay
outside Git and Docker image layers.
