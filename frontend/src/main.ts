import { createApp } from "vue";
import App from "./app/App.vue";
import router from "./router";
import "./shared/design/tokens.css";
import { installLiquidGlass } from "./shared/design/liquid-glass";

createApp(App).use(router).mount("#app");
installLiquidGlass();
