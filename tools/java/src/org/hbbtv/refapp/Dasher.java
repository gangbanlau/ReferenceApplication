package org.hbbtv.refapp;

import java.util.*;
import java.io.*;

/**
 * MAIN CLASS: Transcode, Dash, DashEncrypt input video file.
 * Example command, output if current working directory:
 *   java -jar /refapp/lib/dasher.jar config=dasher.properties logfile=/videos/file1/manifest-log.txt drm.kid=rng drm.key=rng input=/videos/file1.mp4 output=/videos/file1/
 */
public class Dasher {
	public static String NL = System.getProperty("line.separator", "\r\n");
	private static LogWriter logger;
	
	public static void main(String[] cmdargs) throws Exception {
		Map<String,String> params = Utils.parseParams(cmdargs);

		try {
			File inputFile = new File(params.get("input"));

			String val = Utils.normalizePath(Utils.getString(params, "output", "", true), true);
			if (val.isEmpty() || val.equals("/")) 
				throw new IllegalArgumentException("Invalid output value '" + val +"'");
			if (!val.endsWith("/")) val+="/";
			File outputFolder = new File(val);
			outputFolder.mkdirs();
			
			// delete old files from output folder
			if (Utils.getBoolean(params, "deleteoldfiles", true))
				deleteOldFiles(outputFolder);
			else
				new File(outputFolder, "manifest.mpd").delete();

			val = Utils.getString(params, "logfile", "", true);
			logger = new LogWriter();
			logger.openFile(val.isEmpty() ? null : new File(val));
			
			MediaTools.initTools(params.get("tool.ffmpeg"), params.get("tool.mp4box"));						

			logger.println(Utils.getNowAsString() + " Start dashing");
			logger.println("input="  + Utils.normalizePath(inputFile.getAbsolutePath(), true) );
			logger.println("output=" + Utils.normalizePath(outputFolder.getAbsolutePath(), true) );
			
			logger.println("Parameters:");
			for(String key : new TreeSet<String>(params.keySet()) )
				logger.println(key+"="+params.get(key));
			logger.println("");

			logger.println("Input metadata:");			
			Map<String,String> meta = MediaTools.readMetadata(inputFile);			
			for(String key : meta.keySet())
				logger.println(key+"="+meta.get(key));
			
			int fps = (int)Utils.getLong(meta, "videoFPS", 25);
			int gopdur = (int)Utils.getLong(params, "gopdur", 3); // GOP duration in seconds
			String overlayOpt = Utils.getString(params, "overlay", "0", true); // 1=enabled, 0=disabled

			val = Utils.getString(params, "mode", "", true); // h264,h265 
			StreamSpec.TYPE mode = val.equalsIgnoreCase("h265") ?
					StreamSpec.TYPE.VIDEO_H265 : StreamSpec.TYPE.VIDEO_H264;

			// create preview images
			// size: 640x360
			int timeSec=(int)Utils.getLong(params, "image.seconds", -1);
			if (timeSec>=0) {
				//FIXME: use image.1 then use JavaImage scaler to resize rest of the images.
				logger.println("");
				for(int idx=1; ; idx++) {
					val = Utils.getString(params, "image."+idx, "", true); // 640x360
					if (val.isEmpty()) break;
					List<String> args=MediaTools.getImageArgs(inputFile, timeSec, val);
					logger.println(Utils.getNowAsString()+" "+ Utils.implodeList(args, " "));
					if (!val.endsWith("disable"))
						MediaTools.executeProcess(args, outputFolder);				
				}
			}
			
			List<StreamSpec> specs=new ArrayList<StreamSpec>();

			// transcode video.1,video.2,.. output streams
			// name size bitrate: v1 640x360 512k
			logger.println("");			
			for(int idx=1; ; idx++) {
				val = Utils.getString(params, "video."+idx, "", true);
				if (val.isEmpty()) {
					if (idx<=5) continue; // try video.1..5 then give up.
					else break; // end of video.X array  
				}
				
				boolean isDisabled = val.endsWith("disable");
				String[] valopts = val.split(" ");
				StreamSpec spec = new StreamSpec();
				spec.type = mode; // video mode is h264 or h265 
				spec.name = valopts[0].trim();
				spec.size = valopts[1].toLowerCase(Locale.US).trim();
				spec.bitrate = valopts[2].toLowerCase(Locale.US).trim();
				spec.enabled = !isDisabled;

				val = Utils.getString(params, "video."+idx+".profile", "", true);
				if (val.isEmpty()) val = Utils.getString(params, "video.profile", "", true);
				spec.profile=val;

				val = Utils.getString(params, "video."+idx+".level", "", true);
				if (val.isEmpty()) val = Utils.getString(params, "video.level", "", true);
				spec.level=val;

				specs.add(spec);

				List<String> args = spec.type==StreamSpec.TYPE.VIDEO_H265 ?
						MediaTools.getTranscodeH265Args(inputFile, spec, fps, gopdur, overlayOpt) :
						MediaTools.getTranscodeH264Args(inputFile, spec, fps, gopdur, overlayOpt);
				logger.println(Utils.getNowAsString()+" "+ Utils.implodeList(args, " "));
				
				if (!isDisabled) {
					MediaTools.executeProcess(args, outputFolder);
					if (spec.type==StreamSpec.TYPE.VIDEO_H265) {
						// convert temp-v1.mp4(HEV1) to temp-v1.mp4(HVC1), this works better in dash devices. 
						// TODO: create hvc1 directly in ffmpeg?
						logger.println(String.format("%s Convert HEV1 to HVC1 (name=%s)", Utils.getNowAsString(), spec.name));
						MediaTools.convertHEV1toHVC1(outputFolder, spec);
					}					
					//String buf = MediaTools.executeProcess(args, outputFolder);
					//if (NL.length()>1) buf=buf.replace("\n", NL); 
					//println(buf);
				}
			}

			// transcode audio.1,audio.2,.. output streams
			// name samplerate bitrate channels: a1 48000 128k 2
			logger.println("");			
			for(int idx=1; ; idx++) {
				val = Utils.getString(params, "audio."+idx, "", true);
				if (val.isEmpty()) break;
				boolean isDisabled = val.endsWith("disable");				
				String[] valopts = val.split(" "); 
				StreamSpec spec = new StreamSpec();
				spec.type = StreamSpec.TYPE.AUDIO_AAC;
				spec.name = valopts[0].trim();
				spec.sampleRate = Integer.parseInt(valopts[1].trim());
				spec.bitrate = valopts[2].toLowerCase(Locale.US).trim();
				spec.channels = Integer.parseInt(valopts[3].trim());
				spec.enabled = !isDisabled;
				specs.add(spec);
				
				List<String> args=MediaTools.getTranscodeAACArgs(inputFile, spec);
				logger.println(Utils.getNowAsString()+" "+ Utils.implodeList(args, " "));
				if (!isDisabled)
					MediaTools.executeProcess(args, outputFolder);
			}
			
			// transcode secondary audio inputs, append new spec to StreamSpec list
			logger.println("");		
			for(int idxI=1, specCount=specs.size(); ; idxI++) {
				val = Utils.getString(params, "input."+idxI, "", true);
				if (val.isEmpty()) break;
				File inputFileSec = new File(val);
				for(int idx=0; idx<specCount; idx++) {
					StreamSpec spec = specs.get(idx);
					if (spec.type != StreamSpec.TYPE.AUDIO_AAC || !spec.enabled) continue;
					spec = (StreamSpec)spec.clone();
					spec.name = spec.name+"-"+idxI;
					specs.add(spec);
					List<String> args=MediaTools.getTranscodeAACArgs(inputFileSec, spec);
					logger.println(Utils.getNowAsString()+" "+ Utils.implodeList(args, " "));
					MediaTools.executeProcess(args, outputFolder);
				}
			}
			
			// DASH: write unencypted segments+manifest
			logger.println("");
			List<String> args=MediaTools.getDashArgs(specs, (int)Utils.getLong(params, "segdur", 6));
			logger.println(Utils.getNowAsString()+" "+ Utils.implodeList(args, " "));
			MediaTools.executeProcess(args, outputFolder);
			
			// Fix some of the common manifest problems after mp4box tool
			DashManifest manifest = new DashManifest(new File(outputFolder, "manifest.mpd"));
			manifest.fixContent(mode);
			manifest.save( new File(outputFolder, "manifest.mpd") );

			// DASH: write encrypted segments+manifest if KID+KEY values are found
			if (!Utils.getString(params, "drm.kid", "", true).isEmpty() &&
					!Utils.getString(params, "drm.key", "", true).isEmpty()) {
				DashDRM drm = new DashDRM();
				drm.initParams(params);
				params.put("drm.created","1");
				
				logger.println("");
				logger.println("drm.kid="+params.get("drm.kid"));
				logger.println("drm.key="+params.get("drm.key"));
				logger.println("drm.iv="+params.get("drm.iv"));
				logger.println("drm.playready.laurl="+params.get("drm.playready.laurl"));
				
				// delete old files from output folder
				File outputFolderDrm=new File(outputFolder, "drm/");  // write to drm/ subfolder
				outputFolderDrm.mkdir();
				if (Utils.getBoolean(params, "deleteoldfiles", true))
					deleteOldFiles(outputFolderDrm);
				else
					new File(outputFolder, "drm/manifest.mpd").delete();

				// create GPACDRM.xml drm specification file, write to workdir folder
				File specFile=new File(outputFolder, "temp-gpacdrm.xml");
				specFile.delete();
				val=drm.createGPACDRM();				
				logger.println(val);
				FileOutputStream fos = new FileOutputStream(specFile);
				try { fos.write(val.getBytes("UTF-8")); } finally { fos.close(); }
				
				// encrypt temp-v1.mp4 to drm/temp-v1.mp4
				logger.println("");				
				for(StreamSpec spec : specs) {
					if (!spec.enabled) continue;
					args=MediaTools.getDashCryptArgs(specFile, outputFolderDrm, spec); 
					logger.println(Utils.getNowAsString()+" "+ Utils.implodeList(args, " "));
					MediaTools.executeProcess(args, outputFolder); // run in workdir folder
				}

				// dash encrypted segments
				args=MediaTools.getDashArgs(specs, (int)Utils.getLong(params, "segdur", 6));
				logger.println(Utils.getNowAsString()+" "+ Utils.implodeList(args, " "));
				MediaTools.executeProcess(args, outputFolderDrm); // dash drm/temp-*.mp4 files

				// remove moov/trak/senc box from init segments, it breaks some of the hbbtv players,
				// create vX_i_nopssh.mp4 init without any PSSH boxes
				for(StreamSpec spec : specs) {
					if (!spec.enabled) continue;
					File initFile = new File(outputFolder, "drm/"+spec.name+"_i.mp4");
					if (BoxModifier.removeBox(initFile, initFile, "moov/trak/senc"))
						logger.println("Removed moov/trak/senc from " + initFile.getAbsolutePath() );

					File outFile  = new File(outputFolder, "drm/"+spec.name+"_i_nopssh.mp4");					
					if (BoxModifier.removeBox(initFile, outFile, "moov/pssh[*]"))
						logger.println(String.format("Removed moov/pssh[*] from %s to %s"
								, initFile.getAbsolutePath(), outFile.getAbsolutePath()) );
				}
				
				// fix manifest, add missing drmsystem namespaces
				File manifestFile=new File(outputFolder, "drm/manifest.mpd");
				manifest = new DashManifest(manifestFile);
				manifest.fixContent(mode);
				manifest.addNamespaces();
				
				// add <ContentProtection> elements, remove MPEG-CENC element if was disabled
				val=drm.createPlayreadyMPDElement(); 	// "playready"
				if (!val.isEmpty())
					manifest.addContentProtectionElement(val);
				val=drm.createWidevineMPDElement();		// "widevine"
				if (!val.isEmpty())
					manifest.addContentProtectionElement(val);
				val=drm.createMarlinMPDElement();		// "marlin"
				if (!val.isEmpty())
					manifest.addContentProtectionElement(val);
				if (Utils.getString(params, "drm.cenc", "0", true).equals("0"))
					manifest.removeContentProtectionElement("cenc");

				manifest.save(manifestFile);

				// write separate clearkey manifest with just MPEG-CENC+EME-CENC(CLEARKEY) <ContentProtection> elements,
				// some players don't use it if another compatible drm is signalled in a manifest.				
				val=drm.createClearKeyMPDElement();
				if (!val.isEmpty()) {
					manifest.addContentProtectionElement(val);
					manifest.removeContentProtectionElement("playready");
					manifest.removeContentProtectionElement("widevine");
					manifest.removeContentProtectionElement("marlin");
					manifest.save( new File(outputFolder, "drm/manifest_clearkey.mpd") );
				}

				// create manifest where init url points to vX_i_nopssh.mp4 files
				String data = Utils.loadTextFile(manifestFile, "UTF-8").toString();
				data = data.replace("initialization=\"$RepresentationID$_i.mp4\"", "initialization=\"$RepresentationID$_i_nopssh.mp4\"");
				Utils.saveFile(new File(outputFolder, "drm/manifest_nopssh.mpd"), data.getBytes("UTF-8"));
				
				if (Utils.getBoolean(params, "deletetempfiles", true)) {
					specFile.delete();
					for(StreamSpec spec : specs)
						new File(outputFolder, "drm/temp-"+spec.name+".mp4").delete();
				}
			}

			if (Utils.getBoolean(params, "deletetempfiles", true)) {
				for(StreamSpec spec : specs)
					new File(outputFolder, "temp-"+spec.name+".mp4").delete();
			}

			// create subtitles(inband,outband), first call creates segment files
			// and DRM manifests points to the sub files.
			createSubtitlesInband(params, new File(outputFolder, "manifest.mpd"), 
					new File(outputFolder, "manifest_subib.mpd"), 
					true, "");
			createSubtitlesOutband(params, new File(outputFolder, "manifest.mpd"), 
					new File(outputFolder, "manifest_subob.mpd"), 
					true, "");			
			if (Utils.getBoolean(params, "drm.created", false)) {
				createSubtitlesInband(params, new File(outputFolder, "drm/manifest.mpd"), 
					new File(outputFolder, "drm/manifest_subib.mpd"), 
					false, "../");
				createSubtitlesOutband(params, new File(outputFolder, "drm/manifest.mpd"), 
						new File(outputFolder, "drm/manifest_subob.mpd"), 
						false, "../");				
			}
			
			logger.println("");			
			logger.println(Utils.getNowAsString() + " Completed dashing");			
		} finally {
			if (logger!=null) logger.close();
		}
	}

	public static int deleteOldFiles(File folder) throws IOException {
		int count=0;
		String exts[] = new String[] { ".m4s", ".mp4", ".mpd", ".jpg" };
		File[] files=folder.listFiles();
		if (files==null) return 0;
		for(File file : files) {
			if (file.isFile()) {
				String name = file.getName();
				for(int idx=0; idx<exts.length; idx++) {
					if (name.endsWith(exts[idx])) {
						file.delete();
						count++;
					}
				}
			}
		}
		return count;
	}

	private static void createSubtitlesInband(Map<String,String> params, File manifestFile, File manifestOutput,
				boolean splitSegments, String urlPrefix) throws Exception {
		// parse subib.X=sub_fin fin sub_fin.xml
		boolean isFirstSub=true;
		for(int idx=1; ; idx++) {
			String val = Utils.getString(params, "subib."+idx, "", true);
			if (val.isEmpty()) {
				if (idx<=5) continue; // try 1..5 then give up.
				else break;  
			}
			if (val.endsWith("disable")) continue;
			String[] valopts = val.split(" ");

			logger.println("Create subtitles(inband) "+val);			
			SubtitleInserter.insertIB(new File(valopts[2].trim()),	// input "sub_fin.xml" file path 
					isFirstSub?manifestFile:manifestOutput, 
					manifestOutput, 
					valopts[1].trim(),	// lang "fin"
					valopts[0].trim(),  // repId "sub_fin"
					splitSegments, // split xml file to sub_fin/sub_1.m4s segment files
					(int)Utils.getLong(params, "segdur", 6),
					Utils.getBoolean(params, "deletetempfiles", true), 
					urlPrefix);
			isFirstSub=false;			
		}
	}

	private static void createSubtitlesOutband(Map<String,String> params, File manifestFile, File manifestOutput,
			boolean copySubFile, String urlPrefix) throws Exception {
		// parse subob.X=sub_fin fin sub_fin.xml
		boolean isFirstSub=true;
		for(int idx=1; ; idx++) {
			String val = Utils.getString(params, "subob."+idx, "", true);
			if (val.isEmpty()) {
				if (idx<=5) continue; // try 1..5 then give up.
				else break;  
			}
			if (val.endsWith("disable")) continue;
			String[] valopts = val.split(" ");
	
			logger.println("Create subtitles(outband) "+val);			
			SubtitleInserter.insertOB(new File(valopts[2].trim()),	// input "sub_fin.xml" file path 
					isFirstSub?manifestFile:manifestOutput, 
					manifestOutput, 
					valopts[1].trim(),	// lang "fin"
					valopts[0].trim(),  // repId "sub_fin"
					copySubFile, // split xml file to sub_fin/sub_1.m4s segment files
					urlPrefix);
			isFirstSub=false;
		}
	}
	
}
