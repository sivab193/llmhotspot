# Device acceptance test

Target device: Samsung Galaxy S23 Ultra, 12 GB RAM.

Baseline: Qwen3 4B Q4_K_M with an 8192-token context. After the baseline passes, repeat at 16384 tokens as the stretch configuration.

## Phase 0 measurements

Record model, quantization, context, backend, prompt tokens/second, generation tokens/second, battery percent before/after ten minutes, peak battery temperature, and whether the process survives thirty minutes with the screen off.

## MVP gate

- Start in airplane mode with Wi-Fi re-enabled.
- Grant Nearby devices and notification permissions.
- Download or import a tested GGUF model.
- Tap Start hotspot; scan the Wi-Fi QR/pairing text from a laptop.
- Verify `GET /v1/models` rejects no key and succeeds with the displayed key.
- Verify non-streaming and streaming chat completions.
- Verify a FIM request to `/v1/completions`.
- Leave the screen off for thirty minutes and repeat the request.
- Confirm warning at 30% battery and automatic stop at 20%.
- Heat the device under sustained inference; confirm requests pause at 45°C and resume below 42°C.
- Revoke the key and confirm the old key receives HTTP 401.

Example:

```bash
curl http://PHONE_IP:8080/v1/models -H "Authorization: Bearer KEY"
curl http://PHONE_IP:8080/v1/chat/completions \
  -H "Authorization: Bearer KEY" -H "Content-Type: application/json" \
  -d '{"model":"current","messages":[{"role":"user","content":"Write a Kotlin clamp function."}],"stream":true}'
```
