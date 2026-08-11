import { ref } from "vue";

export function useAsyncState<T>(initial: T | null = null) {
  const data = ref<T | null>(initial);
  const pending = ref(false);
  const error = ref<unknown>(null);
  async function run(task: () => Promise<T>): Promise<T> {
    pending.value = true;
    error.value = null;
    try {
      const value = await task();
      data.value = value;
      return value;
    } catch (cause) {
      error.value = cause;
      throw cause;
    } finally {
      pending.value = false;
    }
  }
  return { data, pending, error, run };
}
