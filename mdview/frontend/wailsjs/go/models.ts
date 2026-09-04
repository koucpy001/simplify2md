export namespace main {
	
	export class ImageData {
	    b64: string;
	    mime: string;
	
	    static createFrom(source: any = {}) {
	        return new ImageData(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.b64 = source["b64"];
	        this.mime = source["mime"];
	    }
	}
	export class OpenResult {
	    path: string;
	    content: string;
	    encoding: string;
	    newline: string;
	
	    static createFrom(source: any = {}) {
	        return new OpenResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.content = source["content"];
	        this.encoding = source["encoding"];
	        this.newline = source["newline"];
	    }
	}

	export class DraftInfo {
	    key: string;
	    modTime: number;
	
	    static createFrom(source: any = {}) {
	        return new DraftInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.key = source["key"];
	        this.modTime = source["modTime"];
	    }
	}

	export class UpdateInfo {
	    hasUpdate: boolean;
	    latestTag: string;
	    htmlURL: string;
	
	    static createFrom(source: any = {}) {
	        return new UpdateInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.hasUpdate = source["hasUpdate"];
	        this.latestTag = source["latestTag"];
	        this.htmlURL = source["htmlURL"];
	    }
	}

}

