/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./src/**/*.{cljs,cljc}", "./resources/public/index.html"],
  theme: {
    extend: {
      keyframes: {
        "slide-up": {
          "0%": {
            opacity: 0,
            transform: "translate(-50%, calc(-50% + 16px))"
          },
          "100%": {
            opacity: 1,
            transform: "translate(-50%, -50%)"
          }
        }
      },
      animation: {
        "slide-up": "slide-up 300ms ease-out"
      }
    },
  },
  plugins: [],
}

