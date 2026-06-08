import { defineStore } from "pinia";
import { getCategories } from "@/api/service";
import type { Category } from "@/types/domain";

interface ConfigState {
  categories: Category[];
  loadedAt: number;
}

export const useConfigStore = defineStore("config", {
  state: (): ConfigState => ({
    categories: [],
    loadedAt: 0,
  }),
  actions: {
    async loadCategories(force = false) {
      if (!force && this.categories.length && Date.now() - this.loadedAt < 30 * 60 * 1000) return;
      this.categories = await getCategories();
      this.loadedAt = Date.now();
    },
  },
});
