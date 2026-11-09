package dev.dimension.flare.feature.plugin.abi

/** Shared by installation inspection and execution so both accept the same registrations. */
internal val PLUGIN_REGISTRATION_PRELUDE =
    """
    (() => {
      const freeze = Object.freeze.bind(Object);
      const keys = Object.keys.bind(Object);
      const defineProperty = Object.defineProperty.bind(Object);
      const owns = Function.call.bind(Object.prototype.hasOwnProperty);
      const isArray = Array.isArray.bind(Array);
      const registration = Object.create(null);
      const registered = Object.create(null);
      let count = 0;
      const Seen = Set;
      const hasSeen = Function.call.bind(Set.prototype.has);
      const remember = Function.call.bind(Set.prototype.add);
      let sealed = false;
      const deepFreeze = (value, seen = new Seen()) => {
        if (value === null || (typeof value !== "object" && typeof value !== "function") || hasSeen(seen, value)) return value;
        remember(seen, value);
        for (const key of keys(value)) deepFreeze(value[key], seen);
        return freeze(value);
      };
      const define = (name, type) => defineProperty(globalThis, name, {
        configurable: false,
        writable: false,
        value(value) {
          if (owns(registered, type)) throw new Error(name + " may only be called once");
          if (sealed) throw new Error("Plugin registration is closed");
          if (value === null || typeof value !== "object" || isArray(value)) throw new Error("Invalid plugin registration");
          if (type === "social" && owns(value, "translation")) throw new Error("Register translation with defineFlareTranslatePlugin");
          const definition = deepFreeze(value);
          if (type === "social") {
            for (const key of keys(definition)) defineProperty(registration, key, { value: definition[key], enumerable: true });
          } else {
            defineProperty(registration, type, { value: definition, enumerable: true });
          }
          defineProperty(registered, type, { value: true });
          count++;
        },
      });
      define("defineFlareSocialPlugin", "social");
      define("defineFlareTranslatePlugin", "translation");
      const validate = () => {
        if (count === 0) throw new Error("plugin.js did not call defineFlareSocialPlugin or defineFlareTranslatePlugin");
        sealed = true;
        freeze(registration);
        return true;
      };
      defineProperty(globalThis, "__flareValidateRegistrationV1", { value: validate });
      defineProperty(globalThis, "__flareReadRegistrationV1", { value() { validate(); return registration; } });
    })();
    """.trimIndent() + "\n"
