const form = document.querySelector("#tailor-form");
const fileInput = document.querySelector("#resume-file");
const dropzone = document.querySelector("#dropzone");
const jobDescription = document.querySelector("#job-description");
const tailorButton = document.querySelector("#tailor-button");
const buttonLabel = document.querySelector("#button-label");
const originalText = document.querySelector("#original-text");
const tailoredText = document.querySelector("#tailored-text");
const originalCaption = document.querySelector("#original-caption");
const tailoredCaption = document.querySelector("#tailored-caption");
const downloadButton = document.querySelector("#download-button");
const toast = document.querySelector("#toast");
const providerSelect = document.querySelector("#ai-provider");
const apiKeySettings = document.querySelector("#api-key-settings");
const apiKeyInput = document.querySelector("#openai-api-key");
const providerStatus = document.querySelector("#provider-status");

let resumeText = "";
let tailoredResume = "";
let resumeIsDocx = false;
let toastTimer;

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("visible"), 4500);
}

function updateReadyState() {
  const missingApiKey = providerSelect.value === "openai" && !apiKeyInput.value.trim();
  tailorButton.disabled = !fileInput.files[0] || !jobDescription.value.trim() || !resumeText || missingApiKey;
}

function setBusy(isBusy) {
  fileInput.disabled = isBusy;
  jobDescription.disabled = isBusy;
  providerSelect.disabled = isBusy;
  apiKeyInput.disabled = isBusy;
  tailorButton.disabled = isBusy || !resumeText || !jobDescription.value.trim()
    || (providerSelect.value === "openai" && !apiKeyInput.value.trim());
  buttonLabel.textContent = isBusy ? "Tailoring your resume…" : "Tailor my resume";
}

function updateProviderSettings() {
  const usesOpenAi = providerSelect.value === "openai";
  apiKeySettings.hidden = !usesOpenAi;
  providerStatus.textContent = usesOpenAi ? "OpenAI API" : "Local AI";
  updateReadyState();
}

providerSelect.addEventListener("change", () => {
  if (tailoredResume) {
    tailoredResume = "";
    tailoredText.textContent = "The AI provider changed. Tailor the resume again before exporting.";
    tailoredText.classList.add("empty");
    tailoredCaption.textContent = "Needs tailoring";
    downloadButton.disabled = true;
  }
  updateProviderSettings();
});
apiKeyInput.addEventListener("input", updateReadyState);

async function loadAppSettings() {
  const response = await fetch("/api/settings");
  if (!response.ok) throw new Error(await errorMessage(response));
  const settings = await response.json();
  if (settings.defaultProvider === "ollama" || settings.defaultProvider === "openai") {
    providerSelect.value = settings.defaultProvider;
  }
  updateProviderSettings();
}

loadAppSettings().catch((error) => showToast(`Could not load AI settings: ${error.message}`));

jobDescription.addEventListener("input", () => {
  document.querySelector("#description-count").textContent = `${jobDescription.value.length.toLocaleString()} / 30,000`;
  if (tailoredResume) {
    tailoredResume = "";
    tailoredText.textContent = "The job description changed. Tailor the resume again before exporting.";
    tailoredText.classList.add("empty");
    tailoredCaption.textContent = "Needs tailoring";
    downloadButton.disabled = true;
  }
  updateReadyState();
});

fileInput.addEventListener("change", () => loadResume(fileInput.files[0]));

for (const eventName of ["dragenter", "dragover"]) {
  dropzone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropzone.classList.add("drag-over");
  });
}
for (const eventName of ["dragleave", "drop"]) {
  dropzone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropzone.classList.remove("drag-over");
  });
}
dropzone.addEventListener("drop", (event) => {
  const file = event.dataTransfer.files[0];
  if (!file) return;
  const transfer = new DataTransfer();
  transfer.items.add(file);
  fileInput.files = transfer.files;
  loadResume(file);
});

async function loadResume(file) {
  resumeText = "";
  tailoredResume = "";
  resumeIsDocx = Boolean(file && file.name.toLowerCase().endsWith(".docx"));
  originalText.textContent = "Reading your resume…";
  originalText.classList.add("empty");
  tailoredText.textContent = "Your tailored version will take shape here.";
  tailoredText.classList.add("empty");
  tailoredCaption.textContent = "Ready when you are";
  downloadButton.disabled = true;
  if (!file) {
    originalCaption.textContent = "Waiting for your resume";
    originalText.textContent = "Your resume will appear here when you upload it.";
    updateReadyState();
    return;
  }
  document.querySelector("#upload-title").textContent = file.name;
  document.querySelector("#upload-hint").textContent = resumeIsDocx
    ? `${(file.size / 1024).toFixed(0)} KB · original layout will be preserved`
    : `${(file.size / 1024).toFixed(0)} KB · DOCX required for formatted PDF`;
  downloadButton.title = resumeIsDocx ? "" : "Upload the original DOCX to export with its design";
  originalCaption.textContent = "Extracting text…";
  const body = new FormData();
  body.append("resume", file);
  try {
    const response = await fetch("/api/preview", { method: "POST", body });
    if (!response.ok) throw new Error(await errorMessage(response));
    const preview = await response.json();
    resumeText = preview.text;
    originalText.textContent = resumeText;
    originalText.classList.remove("empty");
    originalCaption.textContent = `${resumeText.length.toLocaleString()} characters`;
    document.querySelector("#upload-hint").textContent = "Resume ready · click to replace";
  } catch (error) {
    originalCaption.textContent = "Could not read this file";
    originalText.textContent = error.message;
    showToast(error.message);
  }
  updateReadyState();
}

async function errorMessage(response) {
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("json")) {
    const problem = await response.json();
    return problem.detail || problem.message || "The request could not be completed.";
  }
  return (await response.text()) || "The request could not be completed.";
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const file = fileInput.files[0];
  if (!file || !resumeText || !jobDescription.value.trim()) return;

  tailoredResume = "";
  tailoredText.textContent = "";
  tailoredText.classList.remove("empty");
  const usesOpenAi = providerSelect.value === "openai";
  tailoredCaption.textContent = usesOpenAi ? "Working with OpenAI…" : "Working with your local AI…";
  downloadButton.disabled = true;
  setBusy(true);
  let tailoringSucceeded = false;

  const body = new FormData();
  body.append("resume", file);
  body.append("jobDescription", jobDescription.value.trim());
  body.append("provider", providerSelect.value);
  if (usesOpenAi) body.append("apiKey", apiKeyInput.value.trim());
  try {
    const response = await fetch("/api/tailor", { method: "POST", body });
    if (!response.ok) throw new Error(await errorMessage(response));
    const result = await response.json();
    tailoredResume = result.edits || "";
    if (!tailoredResume.trim() || !result.preview?.trim()) {
      throw new Error("The AI provider returned an empty resume. Please try again.");
    }
    tailoredText.textContent = result.preview;
    tailoredText.classList.remove("empty");
    tailoredCaption.textContent = resumeIsDocx ? "Ready to download" : "Original DOCX required for export";
    tailoringSucceeded = true;
  } catch (error) {
    tailoredCaption.textContent = "Tailoring stopped";
    tailoredText.textContent = error.message.includes("Failed to fetch")
      ? "Could not connect to the app. Make sure the application is running."
      : error.message;
    tailoredText.classList.add("empty");
    showToast(error.message.includes("Failed to fetch")
      ? "Could not connect to the app. Make sure it is running."
      : error.message);
  } finally {
    setBusy(false);
    downloadButton.disabled = !tailoringSucceeded || !resumeIsDocx;
  }
});

downloadButton.addEventListener("click", async () => {
  if (!tailoredResume.trim()) return;
  downloadButton.disabled = true;
  const downloadLabel = downloadButton.innerHTML;
  downloadButton.innerHTML = '<span aria-hidden="true">↓</span> Preparing PDF…';
  showToast("First PDF export may take a few minutes while local formatting support is installed.");
  try {
    const body = new FormData();
    body.append("resume", fileInput.files[0]);
    body.append("tailoredText", tailoredResume);
    const response = await fetch("/api/export", { method: "POST", body });
    if (!response.ok) throw new Error(await errorMessage(response));
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = url;
    link.download = "tailored-resume.pdf";
    link.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    showToast(error.message);
  } finally {
    downloadButton.disabled = false;
    downloadButton.innerHTML = downloadLabel;
  }
});
