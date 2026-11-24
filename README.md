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

OpenAI API
----------
If you have an OpenAI paid account, you can link your OpenAI API key to use your own tokens

**OpenAI API keys for paid users**:
1. https://platform.openai.com/api-keys
2. Create new secret key, choose expiration period
3. Copy new API key
4. In the TailorResume app, select “AI Settings” in the top right corner
5. Select “OpenAI API - Online” as your AI provider
6. Paste your API key into the OpenAI API key box
7. That’s it!
