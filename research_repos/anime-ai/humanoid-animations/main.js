import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm';
import { loadMixamoAnimation } from './loadMixamoAnimation.js';
import { GeminiLiveController } from './geminiLiveController.js';
import { LipSyncController } from './lipSyncController.js';
import { getRandomAnimationFile, animationLibrary } from './animationLibrary.js';
import { StageEnvironment } from './stageEnvironment.js';
import { DJPerformanceController } from './djPerformanceController.js';
import { GeminiLiveAI } from './geminiLiveAI.js';
import { AnimationTriggers } from '../animationTriggers.js';
import { TextToSpeechChat } from '../textToSpeechChat.js';
import GUI from 'three/addons/libs/lil-gui.module.min.js';

// renderer
const renderer = new THREE.WebGLRenderer();
renderer.setSize( window.innerWidth, window.innerHeight );
renderer.setPixelRatio( window.devicePixelRatio );
document.body.appendChild( renderer.domElement );

// camera
const camera = new THREE.PerspectiveCamera( 30.0, window.innerWidth / window.innerHeight, 0.1, 100.0 );
camera.position.set( 0.0, 1.0, 5.0 );

// camera controls
const controls = new OrbitControls( camera, renderer.domElement );
controls.screenSpacePanning = true;
controls.target.set( 0.0, 1.0, 0.0 );
controls.update();

// scene
const scene = new THREE.Scene();

// Stage Environment
const stage = new StageEnvironment(scene);
stage.setup({
	platform: true,
	djBooth: true,
	lighting: 'concert',
	background: 'gradient',
	decorations: true
});

// light (basic ambient light, stage handles dramatic lighting)
const light = new THREE.DirectionalLight( 0xffffff, Math.PI * 0.3 );
light.position.set( 1.0, 1.0, 1.0 ).normalize();
scene.add( light );

const defaultModelUrl = '../characters/VRM1_Alicia_Solid.vrm';

// gltf and vrm
let currentVrm = undefined;
let currentAnimationUrl = undefined;
let currentMixer = undefined;
let currentAction = undefined;

// AI Controllers
let geminiLive = null;
let lipSync = null;
let ttsChat = null;
let animationTriggers = null;
let djPerformance = null;
const GEMINI_API_KEY = 'AIzaSyDV4NrwTl-lGAfArmf-C_FWhCt0fzi5ZOg'; // TODO: Replace with your API key

// Expose globals for testing
if (typeof window !== 'undefined') {
	window.currentVrm = undefined;
	window.stage = stage;
	window.currentMixer = undefined;
	window.currentAction = undefined;
	window.geminiLive = null;
	window.lipSync = null;
	window.djPerformance = null;
	window.animationLibrary = animationLibrary;
	window.getRandomAnimationFile = getRandomAnimationFile;
}

const helperRoot = new THREE.Group();
helperRoot.renderOrder = 10000;
scene.add( helperRoot );

function loadVRM( modelUrl ) {

	const loader = new GLTFLoader();
	loader.crossOrigin = 'anonymous';

	helperRoot.clear();

	loader.register( ( parser ) => {

		return new VRMLoaderPlugin( parser, { autoUpdateHumanBones: true } );

	} );

	loader.load(
		// URL of the VRM you want to load
		modelUrl,

		// called when the resource is loaded
		( gltf ) => {

			const vrm = gltf.userData.vrm;

			// calling this function greatly improves the performance
			VRMUtils.removeUnnecessaryVertices( gltf.scene );
			VRMUtils.combineSkeletons( gltf.scene );
			VRMUtils.combineMorphs( vrm );

			if ( currentVrm ) {

				scene.remove( currentVrm.scene );

				VRMUtils.deepDispose( currentVrm.scene );

			}

			// put the model to the scene
			currentVrm = vrm;
			window.currentVrm = vrm; // Expose for testing
			scene.add( vrm.scene );

			// create AnimationMixer for VRM
			currentMixer = new THREE.AnimationMixer( currentVrm.scene );
			window.currentMixer = currentMixer; // Expose for testing

			// Initialize DJ Performance Controller
			djPerformance = new DJPerformanceController(currentVrm, currentMixer);
			window.djPerformance = djPerformance; // Expose for testing
			console.log('🎧 DJ Performance Controller initialized');

			// Disable frustum culling
			vrm.scene.traverse( ( obj ) => {

				obj.frustumCulled = false;

			} );

			if ( currentAnimationUrl ) {

				loadFBX( currentAnimationUrl );

			} else {

				// Load single breathing idle animation and loop it
				loadFBX( './Breathing Idle.fbx' );

			}

			// rotate if the VRM is VRM0.0
			VRMUtils.rotateVRM0( vrm );

			// Initialize AI controllers
			initializeAI();

			console.log( vrm );

		},

		// called while loading is progressing
		( progress ) => console.log( 'Loading model...', 100.0 * ( progress.loaded / progress.total ), '%' ),

		// called when loading has errors
		( error ) => console.error( error ),
	);

}

loadVRM( defaultModelUrl );

// AI System Initialization
function initializeAI() {
	if (!currentVrm) return;
	
	// Initialize lip-sync controller
	lipSync = new LipSyncController(currentVrm);
	window.lipSync = lipSync;
	
	// Initialize Animation Triggers with animation + audio handlers
	animationTriggers = new AnimationTriggers(
		handleAnimationCommand,
		playAudioFile
	);
	window.animationTriggers = animationTriggers;
	
	// Initialize Text-to-Speech Chat
	ttsChat = new TextToSpeechChat(GEMINI_API_KEY);
	
	// Setup TTS chat callbacks
	ttsChat.onAudioStart = () => {
		console.log('🔊 AI is speaking...');
		if (lipSync) {
			lipSync.startTalking();
		}
	};
	
	ttsChat.onAudioEnd = () => {
		console.log('🔇 AI finished speaking');
		if (lipSync) {
			lipSync.stopTalking();
		}
	};
	
	ttsChat.onTextResponse = (text) => {
		console.log('💬 AI response:', text);
		// Check for animation keywords in AI response
		if (animationTriggers) {
			animationTriggers.processAIResponse(text);
		}
		// Trigger callback for UI
		if (typeof window.onAIResponse === 'function') {
			window.onAIResponse(text);
		}
	};
	
	ttsChat.onError = (error) => {
		console.error('❌ TTS Chat error:', error);
	};
	
	// Expose to window
	window.ttsChat = ttsChat;
	
	console.log('✅ AI System initialized (with TTS Chat + Animation Triggers)');
}

// Audio Player for pre-recorded voice clips
const audioContext = new (window.AudioContext || window.webkitAudioContext)();
let currentAudioSource = null;

async function playAudioFile(filename) {
	try {
		console.log(`🔊 Playing audio: ${filename}`);
		
		// Stop current audio if playing
		if (currentAudioSource) {
			currentAudioSource.stop();
			currentAudioSource = null;
		}
		
		// Start lip-sync
		if (lipSync) {
			lipSync.startTalking();
		}
		
		// Fetch and decode audio
		const response = await fetch(`./audio/${filename}`);
		const arrayBuffer = await response.arrayBuffer();
		const audioBuffer = await audioContext.decodeAudioData(arrayBuffer);
		
		// Get audio duration
		const audioDuration = audioBuffer.duration;
		console.log(`⏱️ Audio duration: ${audioDuration.toFixed(2)}s`);
		
		// Create and play source
		currentAudioSource = audioContext.createBufferSource();
		currentAudioSource.buffer = audioBuffer;
		currentAudioSource.connect(audioContext.destination);
		
		// Stop lip-sync when audio ends
		currentAudioSource.onended = () => {
			console.log('🔇 Audio finished');
			if (lipSync) {
				lipSync.stopTalking();
			}
			currentAudioSource = null;
		};
		
		currentAudioSource.start(0);
		
		// Return audio duration
		return audioDuration;
		
	} catch (error) {
		console.error('❌ Audio playback error:', error);
		if (lipSync) {
			lipSync.stopTalking();
		}
		return null;
	}
}

// Expose audio player to window
window.playAudioFile = playAudioFile;

// Handle animation commands from AI
async function handleAnimationCommand(action, duration, intensity = 1.0) {
	console.log(`🎭 Playing animation: ${action}`);
	
	// Get random file from action category
	const animData = getRandomAnimationFile(action);
	if (!animData) {
		console.warn(`❌ Animation not found: ${action}`);
		return;
	}
	
	// Load and play animation
	// Try multiple possible paths
	const possiblePaths = [
		`./${animData.file}`,                           // Same directory (for test.html)
		`./humanoid-animations/${animData.file}`,       // From root (for live.html in root)
		`../${animData.file}`                            // Parent directory
	];
	
	let loaded = false;
	for (const animUrl of possiblePaths) {
		console.log(`📁 Trying: ${animUrl}`);
		try {
			await loadFBX(animUrl);
			console.log(`✅ Animation loaded: ${animUrl}`);
			loaded = true;
			break;
		} catch (error) {
			console.log(`⚠️  Not found at: ${animUrl}`);
		}
	}
	
	if (!loaded) {
		console.error(`❌ Failed to load animation: ${animData.file} from all paths`);
		return;
	}
	
	// Use duration from AI or default
	const playDuration = duration || animData.defaultDuration;
	
	// Auto-return to idle after duration (if not looping)
	if (playDuration > 0) {
		setTimeout(() => {
			loadFBX('./Breathing Idle.fbx');
		}, playDuration * 1000);
	}
}

// Handle audio output from AI (for lip-sync)
function handleAudioOutput(audioData) {
	if (lipSync) {
		lipSync.playAudio(audioData);
	}
}

// Connect to Gemini Live API
async function connectAI() {
	if (!currentVrm) {
		alert('Please load VRM model first!');
		return false;
	}
	
	if (!geminiLive) {
		// Initialize GeminiLiveAI
		geminiLive = new GeminiLiveAI(GEMINI_API_KEY);
		
		// Setup callbacks
		geminiLive.onResponse((type, data) => {
			if (type === 'text') {
				console.log('🤖 AI:', data);
				if (window.onAIResponse) {
					window.onAIResponse(data);
				}
			}
		});
		
		geminiLive.onAudio((event) => {
			if (event === 'playing') {
				// Trigger mouth animation during speech
				if (lipSync) {
					lipSync.startTalking();
				}
			} else if (event === 'ended') {
				if (lipSync) {
					lipSync.stopTalking();
				}
			}
		});
		
		// Setup animation callback
		geminiLive.onAnimation((action) => {
			console.log(`🎬 Triggering animation: ${action}`);
			handleAnimationCommand(action);
		});
		
		window.geminiLive = geminiLive;
	}
	
	const success = await geminiLive.connect();
	if (success) {
		console.log('🎤 AI Live connection active!');
		return true;
	}
	return false;
}

// Disconnect from AI
function disconnectAI() {
	if (geminiLive) {
		geminiLive.disconnect();
		console.log('👋 AI disconnected');
	}
}

// Send text to AI (for testing without mic)
async function sendTextToAI(text) {
	if (geminiLive && geminiLive.isConnected) {
		await geminiLive.sendText(text);
	} else {
		console.warn('AI not connected');
	}
}

// Expose functions for testing
if (typeof window !== 'undefined') {
	window.loadFBX = loadFBX;
	window.handleAnimationCommand = handleAnimationCommand;
	window.handleAudioOutput = handleAudioOutput;
	window.connectAI = connectAI;
	window.disconnectAI = disconnectAI;
	window.sendTextToAI = sendTextToAI;
}

// mixamo animation
async function loadFBX( animationUrl ) {

	currentAnimationUrl = animationUrl;

	if ( currentMixer ) {

		// Load animation
		const clip = await loadMixamoAnimation( animationUrl, currentVrm );

		const newAction = currentMixer.clipAction( clip );
		newAction.reset().play();
		newAction.loop = THREE.LoopRepeat; // Ensure it loops

		if ( currentAction && currentAction !== newAction ) {

			currentAction.crossFadeTo( newAction, 0.5, false );

		}

		currentAction = newAction;
		window.currentAction = newAction; // Expose for testing

	}

}

// Function to load and play a sequence of animations in loop
async function loadSequence( urls ) {

	if ( !currentMixer || !currentVrm ) return;

	const actions = [];
	const clips = [];

	// Load all clips
	for ( const url of urls ) {
		const clip = await loadMixamoAnimation( url, currentVrm );
		clips.push( clip );
		const action = currentMixer.clipAction( clip );
		action.loop = THREE.LoopOnce;
		action.clampWhenFinished = false;
		actions.push( action );
	}

	let currentIndex = 0;

	function playNext() {
		const action = actions[ currentIndex ];
		action.reset().play();
		currentAction = action;

		// Schedule crossfade to next before finish
		const nextIndex = ( currentIndex + 1 ) % actions.length;
		const nextAction = actions[ nextIndex ];
		const duration = clips[ currentIndex ].duration;
		setTimeout( () => {
			action.crossFadeTo( nextAction, 0.5, false );
			nextAction.play();
			currentAction = nextAction;
		}, ( duration - 0.5 ) * 1000 ); // Crossfade 0.5s before end

		currentIndex = nextIndex;
	}

	// Start sequence
	playNext();

}

// helpers (commented out - stage has its own grid)
// const gridHelper = new THREE.GridHelper( 10, 10 );
// scene.add( gridHelper );

// const axesHelper = new THREE.AxesHelper( 5 );
// scene.add( axesHelper );

// animate
const clock = new THREE.Clock();

function animate() {

	requestAnimationFrame( animate );

	const deltaTime = clock.getDelta();

	// if animation is loaded
	if ( currentMixer ) {

		// update the animation
		currentMixer.update( deltaTime );

	}

	if ( currentVrm ) {

		// Lip-sync is now handled by LipSyncController
		// Only use procedural if not using AI voice
		if (!lipSync || !lipSync.isPlaying) {
			// Idle breathing
			const mouthS = 0.05 + 0.05 * Math.sin( Math.PI * clock.elapsedTime * 0.5 );
			currentVrm.expressionManager.setValue( 'aa', mouthS );
		}

		currentVrm.update( deltaTime );

	}

	// update stage (animated lighting effects)
	stage.update(deltaTime, clock.elapsedTime);

	renderer.render( scene, camera );

}

animate();

// gui
const gui = new GUI();

const params = {
	timeScale: 1.0,
	aiConnected: false,
	testMessage: '',
};

gui.add( params, 'timeScale', 0.0, 2.0, 0.001 ).onChange( ( value ) => {
	currentMixer.timeScale = value;
} );

// AI Controls folder
const aiFolder = gui.addFolder('🤖 AI Live Control');

const aiControls = {
	connect: async () => {
		params.aiConnected = await connectAI();
	},
	disconnect: () => {
		disconnectAI();
		params.aiConnected = false;
	},
	sendText: () => {
		if (params.testMessage) {
			sendTextToAI(params.testMessage);
			params.testMessage = '';
		}
	}
};

aiFolder.add(aiControls, 'connect').name('🎤 Connect Voice');
aiFolder.add(aiControls, 'disconnect').name('⏸️ Disconnect');
aiFolder.add(params, 'testMessage').name('Test Text');
aiFolder.add(aiControls, 'sendText').name('📤 Send Text');
aiFolder.open();

// Animation test folder
const animFolder = gui.addFolder('🎭 Test Animations');

Object.keys(animationLibrary).forEach(action => {
	animFolder.add({
		play: () => handleAnimationCommand(action, animationLibrary[action].duration)
	}, 'play').name(`${action} (${animationLibrary[action].duration}s)`);
});

animFolder.open();

// DJ Performance folder
const djFolder = gui.addFolder('🎧 DJ Performance');

const djControls = {
	startPerformance: () => {
		if (djPerformance) {
			djPerformance.startPerformance();
		} else {
			console.warn('Load VRM model first!');
		}
	},
	stopPerformance: () => {
		if (djPerformance) djPerformance.stopPerformance();
	},
	playDancing: () => {
		if (djPerformance) djPerformance.playDancing();
	},
	playInteraction: () => {
		if (djPerformance) djPerformance.playInteraction();
	},
	energyLow: () => {
		if (djPerformance) djPerformance.setEnergy('low');
	},
	energyMedium: () => {
		if (djPerformance) djPerformance.setEnergy('medium');
	},
	energyHigh: () => {
		if (djPerformance) djPerformance.setEnergy('high');
	}
};

djFolder.add(djControls, 'startPerformance').name('▶️ Start Auto Performance');
djFolder.add(djControls, 'stopPerformance').name('⏸️ Stop Performance');
djFolder.add(djControls, 'playDancing').name('🕺 Random Dance');
djFolder.add(djControls, 'playInteraction').name('👋 Random Interaction');
const energyFolder = djFolder.addFolder('⚡ Energy Level');
energyFolder.add(djControls, 'energyLow').name('🔵 Low (Chill)');
energyFolder.add(djControls, 'energyMedium').name('🟢 Medium (Balanced)');
energyFolder.add(djControls, 'energyHigh').name('🔴 High (Intense)');
djFolder.open();

// file input

// dnd handler
window.addEventListener( 'dragover', function ( event ) {

	event.preventDefault();

} );

window.addEventListener( 'drop', function ( event ) {

	event.preventDefault();

	// read given file then convert it to blob url
	const files = event.dataTransfer.files;
	if ( ! files ) return;

	const fbxFiles = Array.from(files).filter(file => file.name.endsWith('.fbx'));
	const vrmFiles = Array.from(files).filter(file => file.name.endsWith('.vrm'));

	if ( fbxFiles.length === 2 ) {
		// Load two FBX animations in sequence
		const url1 = URL.createObjectURL( new Blob( [ fbxFiles[0] ], { type: 'application/octet-stream' } ) );
		const url2 = URL.createObjectURL( new Blob( [ fbxFiles[1] ], { type: 'application/octet-stream' } ) );
		loadTwoAnimations( url1, url2 );
	} else if ( fbxFiles.length === 1 ) {
		const file = fbxFiles[0];
		const blob = new Blob( [ file ], { type: 'application/octet-stream' } );
		const url = URL.createObjectURL( blob );
		loadFBX( url );
	} else if ( vrmFiles.length > 0 ) {
		const file = vrmFiles[0];
		const blob = new Blob( [ file ], { type: 'application/octet-stream' } );
		const url = URL.createObjectURL( blob );
		loadVRM( url );
	}

} );