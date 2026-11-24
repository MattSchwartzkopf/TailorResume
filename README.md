Upload your existing résumé as a .docx, paste in the job description of the position you're interested in, tailor the resume and download as a PDF!

To run in IDEA IntelliJ in MacOS
--------------------------------

In the **Terminal**, run:
1. Run `Ollama pull llama3.1`
2. Then run `./mvnw spring-boot:run`

Next
----
Open any web browser:
- Navigate to `http://localhost:8080`
- Upload existing resume as .docx and paste in the job description
- Click `Tailor My Resume`!

AI providers
------------

The app uses local Ollama by default. To use OpenAI instead, open **AI settings**, choose **OpenAI API**, and enter an OpenAI API key. This is an API key, not a ChatGPT password or ChatGPT subscription; API usage is billed separately. The key is sent to the app only with the tailoring request and is not saved by the app. When using OpenAI, the resume and job description are sent to OpenAI.

For a hosted deployment, set `TAILORING_PROVIDER=openai` so the OpenAI option is selected by default; users provide their own API keys. The app does not need Ollama installed in this mode. Configure the hosting platform or reverse proxy to expose the app over HTTPS, and set `SERVER_ADDRESS=0.0.0.0` if the platform requires the app to listen on its network interface. Do not log request bodies, since tailoring requests contain users' API keys and resume data.

Optional provider settings:

- `OPENAI_MODEL` selects the OpenAI API model (default: `gpt-4o-mini`).
- `OPENAI_BASE_URL` changes the OpenAI-compatible API base URL (default: `https://api.openai.com/v1`).
- `TAILORING_PROVIDER` selects the initial provider (`ollama` or `openai`; default: `ollama`).
