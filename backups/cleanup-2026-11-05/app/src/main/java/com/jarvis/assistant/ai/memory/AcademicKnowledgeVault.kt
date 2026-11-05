package com.jarvis.assistant.ai.memory

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-Device Academic & Physics Knowledge Vault.
 * Seeds persistent vector memory with fundamental Class 1-12 CBSE/HSC Science,
 * Physics Laws, Universal Constants, Chemistry, and Mathematics equations.
 */
object AcademicKnowledgeVault {

    private const val TAG = "AcademicKnowledgeVault"
    private const val PREFS_NAME = "jarvis_academic_vault_prefs"
    private const val KEY_SEEDED_VERSION = "key_academic_seeded_version"
    private const val CURRENT_VAULT_VERSION = 1

    /**
     * Complete compendium of foundational Physics, Chemistry, and Mathematics principles.
     */
    val ACADEMIC_KNOWLEDGE_BASE = listOf(
        // --- PHYSICS: CLASS 11 & 12 CORE LAWS & MECHANICS ---
        "Newton's First Law of Motion: An object remains at rest or in uniform motion along a straight line unless acted upon by an external net force (Law of Inertia).",
        "Newton's Second Law of Motion: The rate of change of momentum of a body is directly proportional to the applied force: F = dp/dt = m * a (Force = Mass * Acceleration).",
        "Newton's Third Law of Motion: To every action, there is always an equal and opposite reaction (F_AB = -F_BA).",
        "Universal Law of Gravitation: Every particle attracts every other particle with a force proportional to the product of their masses and inversely proportional to the square of the distance: F = G * (m1 * m2) / r^2, where G = 6.67430e-11 N m^2/kg^2.",
        "Work-Energy Theorem: The net work done on a particle equals the change in its kinetic energy: W_net = Delta K = (1/2)*m*v_f^2 - (1/2)*m*v_i^2.",
        "Conservation of Momentum: In an isolated system with no external forces, total linear momentum remains constant before and after collision: p_initial = p_final.",
        "Rotational Dynamics: Torque tau = r x F = I * alpha, where I is the moment of inertia and alpha is angular acceleration. Angular momentum L = I * omega.",
        "Hooke's Law & Simple Harmonic Motion (SHM): Restoring force F = -k * x. Time period of simple pendulum T = 2 * pi * sqrt(L / g).",
        "Bernoulli's Principle: For an inviscid, incompressible fluid flow: P + (1/2)*rho*v^2 + rho*g*h = constant (Conservation of energy in fluid mechanics).",

        // --- PHYSICS: THERMODYNAMICS & HEAT ---
        "Zeroth Law of Thermodynamics: If two thermodynamic systems are each in thermal equilibrium with a third system, then they are in thermal equilibrium with each other (defines temperature).",
        "First Law of Thermodynamics: Change in internal energy Delta U = Q - W, where Q is heat added to the system and W is work done by the system (Conservation of energy).",
        "Second Law of Thermodynamics: Heat cannot spontaneously flow from a colder body to a hotter body. Total entropy of an isolated system always increases over time (Delta S >= 0).",
        "Third Law of Thermodynamics: As the temperature of a system approaches absolute zero (0 Kelvin), the entropy of a pure crystalline substance approaches zero.",
        "Ideal Gas Law: P * V = n * R * T, where R = 8.314 J/(mol*K) is the universal gas constant.",

        // --- PHYSICS: ELECTRODYNAMICS & MAGNETISM ---
        "Coulomb's Law of Electrostatics: Force between two point charges F = (1 / (4*pi*epsilon_0)) * (|q1 * q2| / r^2), where 1/(4*pi*epsilon_0) = 8.99e9 N m^2/C^2.",
        "Ohm's Law: Voltage V = I * R (Current is directly proportional to potential difference across a conductor at constant temperature). Electrical power P = V * I = I^2 * R.",
        "Kirchhoff's Laws: 1. Current Law (KCL): Sum of currents entering a junction equals sum of currents leaving (Conservation of charge). 2. Voltage Law (KVL): Sum of potential differences around any closed loop is zero (Conservation of energy).",
        "Gauss's Law for Electrostatics: Electric flux through any closed surface equals net enclosed charge divided by permittivity: Phi_E = closed_integral(E * dA) = Q_enclosed / epsilon_0.",
        "Ampere's Circuital Law & Biot-Savart Law: Magnetic field dB = (mu_0 / 4*pi) * (I * dl x r_hat) / r^2. Relates electric currents to magnetic fields.",
        "Faraday's Law of Induction: Induced electromotive force (EMF) epsilon = -d(Phi_B)/dt (negative sign from Lenz's Law indicating opposition to magnetic flux change).",
        "Lorentz Force: Total electromagnetic force on a charged particle F = q * (E + v x B).",

        // --- PHYSICS: OPTICS & MODERN PHYSICS ---
        "Snell's Law of Refraction: n1 * sin(theta_1) = n2 * sin(theta_2), where n is the refractive index of the medium.",
        "Lens Formula and Mirror Formula: 1/f = 1/v - 1/u (Lens formula), 1/f = 1/v + 1/u (Mirror formula), where f is focal length, v is image distance, u is object distance.",
        "Einstein's Mass-Energy Equivalence: E = m * c^2, where c = 2.99792e8 m/s is the speed of light in vacuum.",
        "Photoelectric Effect: Incident photon energy h * nu = phi + K_max, where h is Planck's constant (6.626e-34 J*s) and phi is the material work function.",
        "De Broglie Wavelength: Matter possesses dual wave-particle nature with wavelength lambda = h / p = h / (m * v).",
        "Bohr Model of Hydrogen Atom: Angular momentum of electrons is quantized: L = m*v*r = n*h / (2*pi). Energy levels E_n = -13.6 eV / n^2.",
        "Radioactive Decay Law: N(t) = N_0 * e^(-lambda * t). Half-life T_half = ln(2) / lambda = 0.693 / lambda.",

        // --- CHEMISTRY: ATOMIC STRUCTURE & CORE PRINCIPLES ---
        "Avogadro's Number: 1 mole of any substance contains exactly 6.02214076e23 particles (atoms, molecules, or ions).",
        "Periodic Law: The chemical and physical properties of elements are periodic functions of their atomic numbers (Mendeleev / Modern Periodic Table).",
        "Pauli Exclusion Principle: No two electrons in an atom can have the same four quantum numbers (n, l, m_l, m_s).",
        "Heisenberg Uncertainty Principle: It is impossible to simultaneously measure both position and momentum of a subatomic particle with absolute precision: Delta x * Delta p >= h / (4*pi).",
        "pH Scale: Measure of hydrogen ion concentration: pH = -log10[H+]. pH < 7 is acidic, pH = 7 is neutral, pH > 7 is basic/alkaline.",

        // --- MATHEMATICS: CALCULUS & GEOMETRY FOUNDATION ---
        "Calculus - Fundamental Theorem of Calculus: If f is continuous on [a, b], then the definite integral of f from a to b equals F(b) - F(a), where F' = f.",
        "Derivatives Core: d/dx(x^n) = n*x^(n-1); d/dx(sin x) = cos x; d/dx(cos x) = -sin x; d/dx(e^x) = e^x; d/dx(ln x) = 1/x.",
        "Pythagorean Theorem: In a right-angled triangle, hypotenuse^2 = base^2 + perpendicular^2 (a^2 + b^2 = c^2).",
        "Trigonometric Fundamental Identities: sin^2(theta) + cos^2(theta) = 1; 1 + tan^2(theta) = sec^2(theta); 1 + cot^2(theta) = cosec^2(theta)."
    )

    /**
     * Seeds the academic compendium into Jarvis's Room Database and Vector Memory Engine on app startup.
     */
    fun seedVaultAsync(
        context: Context,
        repository: MemoryRepository,
        vectorEngine: VectorMemoryEngine,
        scope: CoroutineScope
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seededVersion = prefs.getInt(KEY_SEEDED_VERSION, 0)

        if (seededVersion >= CURRENT_VAULT_VERSION) {
            Log.i(TAG, "Academic Knowledge Vault already seeded (version $seededVersion). Skipping.")
            return
        }

        scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Seeding ${ACADEMIC_KNOWLEDGE_BASE.size} master science, physics, and academic facts into persistent core...")
            var count = 0

            for (fact in ACADEMIC_KNOWLEDGE_BASE) {
                try {
                    val key = fact.take(35).trim()
                    val id = repository.saveMemory(
                        rawText = fact,
                        category = "Academic & Physics",
                        key = key,
                        value = fact
                    )
                    vectorEngine.indexMemory(id, fact, "Academic & Physics")
                    count++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed seeding fact: ${e.message}")
                }
            }

            prefs.edit().putInt(KEY_SEEDED_VERSION, CURRENT_VAULT_VERSION).apply()
            Log.i(TAG, "Academic Knowledge Vault successfully seeded ($count entries indexed into Vector Core).")
        }
    }

    /**
     * Strict keyword search to answer academic, physics, and science questions directly on-device.
     * Prevents false triggers by verifying explicit scientific intent and requiring exact whole-word matches.
     */
    fun findRelevantFact(query: String): String? {
        val clean = query.trim().lowercase(java.util.Locale.ROOT)
        if (clean.isBlank()) return null

        // Explicit scientific domain triggers required to activate academic vault
        val scienceMarkers = listOf(
            "physics", "chemistry", "science", "formula", "equation", "theorem", "niyam",
            "law of", "constant", "si unit", "derivative", "calculus", "newton", "ohm",
            "coulomb", "einstein", "faraday", "snell", "bernoulli", "hooke", "thermodynamics",
            "entropy", "kirchhoff", "gauss", "lorentz", "ampere", "biot", "photoelectric",
            "de broglie", "heisenberg", "avogadro", "pythagor", "gravitation", "gravity",
            "relativity", "quantum", "kinematics", "shm", "electromagnet"
        )

        val hasScienceIntent = scienceMarkers.any { marker ->
            if (marker.contains(" ")) clean.contains(marker)
            else Regex("\\b${Regex.escape(marker)}\\b").containsMatchIn(clean)
        }

        // If the user did NOT ask about science or physics, NEVER return a formula!
        if (!hasScienceIntent) return null

        val stopWords = setOf(
            "what", "is", "the", "law", "tell", "me", "about", "kya", "hai", "batao",
            "bataiye", "explain", "formula", "of", "in", "and", "sir", "please", "ka", "ki", "ke",
            "define", "tum", "hum", "karo", "mujhe", "mera", "meri", "kuch", "apna", "aap"
        )
        val queryTokens = clean.split(Regex("[^a-zA-Z0-9]+")).filter { it.length > 2 && it !in stopWords }
        if (queryTokens.isEmpty()) return null

        var bestMatch: String? = null
        var maxMatches = 0

        for (fact in ACADEMIC_KNOWLEDGE_BASE) {
            val factLower = fact.lowercase(java.util.Locale.ROOT)
            val factWordSet = factLower.split(Regex("[^a-zA-Z0-9]+")).toSet()
            var matches = 0
            for (token in queryTokens) {
                // Whole-word matching only — NEVER substring (e.g. 'tum' will NOT match 'momentum')
                if (token in factWordSet) {
                    matches++
                }
            }
            if (matches > maxMatches) {
                maxMatches = matches
                bestMatch = fact
            }
        }

        // Require at least 2 whole-word matches or a high-confidence match
        return if (maxMatches >= 2 || (maxMatches >= 1 && scienceMarkers.any { clean.contains(it) })) bestMatch else null
    }
}

