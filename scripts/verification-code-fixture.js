const fs = require("node:fs");

function readCapturedCodes(filePath) {
  try {
    return fs.readFileSync(filePath, "utf8")
      .split(/\r?\n/)
      .filter(Boolean)
      .map((line) => JSON.parse(line));
  } catch (error) {
    if (error && error.code === "ENOENT") return [];
    throw error;
  }
}

async function waitForCapturedCode(filePath, email, purpose = "register", afterCount = 0) {
  const deadline = Date.now() + 8_000;
  while (Date.now() < deadline) {
    const matches = readCapturedCodes(filePath)
      .filter((entry) => entry.email === email && entry.purpose === purpose);
    if (matches.length > afterCount) {
      return { code: matches.at(-1).code, count: matches.length };
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("verification code fixture was not captured");
}

module.exports = { readCapturedCodes, waitForCapturedCode };
