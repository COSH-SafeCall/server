package com.safecall.service.user.service;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.api.AuthDtos.Permission;
import com.safecall.service.auth.api.AuthDtos.ProfileView;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.auth.repository.AuthRows.*;
import com.safecall.service.auth.service.AuthTransactions;
import com.safecall.service.common.crypto.*;
import com.safecall.service.common.error.*;
import com.safecall.service.user.api.UserDtos.*;
import com.safecall.service.user.repository.UserRepository;
import com.safecall.service.user.repository.UserRepository.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED, noRollbackFor=SessionInvalidException.class)
public class UserTransactions {
	private final AuthTransactions authentication;
	private final AuthRepository auth;
	private final UserRepository repository;
	private final UserKeyStore keys;
	private final SecretCrypto crypto;
	private final JsonMapper mapper;
	private final Clock clock;
	public UserTransactions(AuthTransactions authentication, AuthRepository auth, UserRepository repository,
		UserKeyStore keys, SecretCrypto crypto, JsonMapper mapper, Clock clock) {
		this.authentication=authentication; this.auth=auth; this.repository=repository; this.keys=keys;
		this.crypto=crypto; this.mapper=mapper; this.clock=clock;
	}
	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
	private Session member(String access) { return authentication.member(access,false); }
	private User user(Session session) { return auth.user(session.userId(),false); }
	private byte[] seal(byte[] key, UUID owner, String field, String value) {
		return crypto.seal(key,owner+":"+field,value==null ? null : value.getBytes(StandardCharsets.UTF_8));
	}
	private String open(byte[] key, UUID owner, String field, byte[] value) {
		return value==null ? null : new String(crypto.open(key,owner+":"+field,value),StandardCharsets.UTF_8);
	}
	private void version(long current, long expected) {
		if (current!=expected) throw new CustomException(ErrorCode.VERSION_CONFLICT);
	}
	public ProfileView profile(String access) {
		Session session=authentication.member(access,false); User user=user(session);
		return profile(user);
	}
	private ProfileView profile(User user) {
		byte[] key=keys.read(user.keyRef());
		String gender=open(key,user.id(),"gender",user.genderCipher());
		String birth=open(key,user.id(),"birthDate",user.birthDateCipher());
		List<String> missing=new ArrayList<>();if(user.nameCipher()==null)missing.add("name");if(user.phoneCipher()==null)missing.add("phone");
		return new ProfileView(open(key,user.id(),"name",user.nameCipher()),gender,
			birth==null ? null : LocalDate.parse(birth),open(key,user.id(),"phone",user.phoneCipher()),
			user.genderSource(),user.birthDateSource(),missing,user.confirmedAt(),user.version());
	}
	public ProfileView updateProfile(String access, ProfileRequest input,UUID key) {
		Session session=member(access); User user=user(session);
		if(input.name()==null || input.name().isBlank() || input.phone()==null || input.phone().isBlank())throw new CustomException(ErrorCode.PROFILE_REQUIRED);
		var request=new ProfileRequest(UserValues.text(input.name(),50),input.gender(),input.birthDate(),UserValues.phone(input.phone()),input.isConfirmed(),input.expectedVersion());
		if(replay(session,"PROFILE_UPDATE",key,request)!=null)return profile(user);
		version(user.version(),request.expectedVersion());
		String name=UserValues.text(request.name(),50), phone=UserValues.phone(request.phone());
		LocalDate birth=UserValues.birthDate(request.birthDate(),LocalDate.ofInstant(now(),ZoneOffset.UTC));
		String gender=request.gender()==null ? null : request.gender().name();
		byte[] hash=crypto.hash("PHONE_MATCH:"+user.id(),phone);
		if (repository.contacts(user.id()).stream().anyMatch(c -> crypto.isEqual(hash,c.phoneHash()))) throw new CustomException(ErrorCode.CONTACT_PHONE_CONFLICT);
		String genderSource=gender==null ? "UNKNOWN" : "USER_CONFIRMED";
		String birthSource=birth==null ? "UNKNOWN" : "USER_CONFIRMED";
		byte[] secret=keys.read(user.keyRef());
		repository.updateProfile(user.id(),seal(secret,user.id(),"name",name),seal(secret,user.id(),"gender",gender),
			seal(secret,user.id(),"birthDate",birth==null ? null : birth.toString()),seal(secret,user.id(),"phone",phone),hash,genderSource,birthSource,now(),request.expectedVersion());
		save(session,"PROFILE_UPDATE",key,request,user.id(),null);
		return profile(auth.user(user.id(),false));
	}
	public Items<ContactView> contacts(String access) {
		User user=user(member(access));
		byte[] key=keys.read(user.keyRef());
		return new Items<>(repository.contacts(user.id()).stream().map(c -> contactView(c,key)).toList());
	}
	private ContactView contactView(Contact c, byte[] key) {
		return new ContactView(c.id(),c.slot(),open(key,c.id(),"name",c.name()),open(key,c.id(),"relationship",c.relationship()),open(key,c.id(),"phone",c.phone()),c.version());
	}
	private Contact contact(UUID user, UUID id) {
		return repository.contacts(user).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(() -> new CustomException(ErrorCode.CONTACT_NOT_FOUND));
	}
	private ContactRequest normalized(ContactRequest request) {
		return new ContactRequest(UserValues.text(request.name(),50),UserValues.text(request.relationship(),30),UserValues.phone(request.phone()));
	}
	private byte[] contactHash(UUID user, UUID excluded, String phone) {
		byte[] hash=crypto.hash("PHONE_MATCH:"+user,phone);
		if (crypto.isEqual(hash,repository.ownPhoneHash(user)) || repository.contacts(user).stream()
			.anyMatch(c -> !c.id().equals(excluded) && crypto.isEqual(hash,c.phoneHash()))) throw new CustomException(ErrorCode.CONTACT_PHONE_CONFLICT);
		return hash;
	}
	public ContactView createContact(String access, ContactRequest input, UUID key) {
		Session session=member(access); User user=user(session); ContactRequest request=normalized(input);
		Replay replay=replay(session,"CONTACT_CREATE",key,request);
		if (replay!=null) return contactView(contact(user.id(),replay.resourceId()),keys.read(user.keyRef()));
		byte[] hash=contactHash(user.id(),null,request.phone());
		List<Contact> existing=repository.contacts(user.id());
		if (existing.size()>=2) throw new CustomException(ErrorCode.CONTACT_LIMIT_REACHED);
		int slot=existing.stream().anyMatch(c -> c.slot()==1) ? 2 : 1;
		UUID id=UUID.randomUUID(); byte[] secret=keys.read(user.keyRef());
		repository.createContact(user.id(),id,slot,seal(secret,id,"name",request.name()),seal(secret,id,"relationship",request.relationship()),seal(secret,id,"phone",request.phone()),hash,now());
		save(session,"CONTACT_CREATE",key,request,id,null);
		return contactView(contact(user.id(),id),secret);
	}
	public ContactView updateContact(String access, UUID id, ContactUpdate input,UUID key) {
		Session session=member(access);User user=user(session);
		var canonical=normalized(new ContactRequest(input.name(),input.relationship(),input.phone()));
		input=new ContactUpdate(canonical.name(),canonical.relationship(),canonical.phone(),input.expectedVersion());
		var updateBody=Map.of("contactId",id,"body",input);
		if(replay(session,"CONTACT_UPDATE",key,updateBody)!=null)return contactView(contact(user.id(),id),keys.read(user.keyRef()));
		Contact existing=contact(user.id(),id); version(existing.version(),input.expectedVersion());
		ContactRequest request=normalized(new ContactRequest(input.name(),input.relationship(),input.phone()));
		byte[] hash=contactHash(user.id(),id,request.phone()), secret=keys.read(user.keyRef());
		repository.updateContact(user.id(),id,seal(secret,id,"name",request.name()),seal(secret,id,"relationship",request.relationship()),seal(secret,id,"phone",request.phone()),hash,now(),input.expectedVersion());
		save(session,"CONTACT_UPDATE",key,updateBody,id,null);
		return contactView(contact(user.id(),id),secret);
	}
	public void deleteContact(String access, UUID id, long expected, UUID key) {
		Session session=member(access); var request=Map.of("contactId",id,"expectedVersion",expected);
		if (replay(session,"CONTACT_DELETE",key,request)!=null) return;
		Contact contact=contact(session.userId(),id); version(contact.version(),expected);
		repository.deleteContact(session.userId(),id); save(session,"CONTACT_DELETE",key,request,id,null);
	}
	public SettingView settings(String access) { return repository.settings(member(access).userId()); }
	public SettingView updateSettings(String access, SettingRequest request,UUID key) {
		Session session=member(access);UUID user=session.userId();
		if(replay(session,"SETTINGS_UPDATE",key,request)!=null)return repository.settings(user);
		version(repository.settings(user).version(),request.expectedVersion());
		try { repository.updateSettings(user,request.incomingAlertMode(),now(),request.expectedVersion()); save(session,"SETTINGS_UPDATE",key,request,user,null); return repository.settings(user); }
		catch (org.springframework.dao.DataAccessException exception) { throw new CustomException(ErrorCode.SETTINGS_SAVE_FAILED); }
	}
	public Items<PermissionView> permissions(String access) {
		return permissionViews(authentication.member(access,false).userId());
	}
	private Items<PermissionView> permissionViews(UUID user) {
		var state=repository.permissions(user);
		return new Items<>(List.of(new PermissionView(PermissionCode.MICROPHONE,state.microphone(),state.updatedAt()),
			new PermissionView(PermissionCode.LOCATION,state.location(),state.updatedAt())));
	}
	public Items<PermissionView> updatePermissions(String access,PermissionsRequest input,UUID key) {
		Session session=authentication.member(access,false);UUID user=session.userId();
		var request=new PermissionsRequest(input.permissions().stream().sorted(Comparator.comparing(PermissionDecision::code)).toList());
		if(request.permissions().stream().map(PermissionDecision::code).distinct().count()!=request.permissions().size())throw new CustomException(ErrorCode.INVALID_REQUEST);
		if(replay(session,"PERMISSIONS_UPDATE",key,request)!=null)return permissionViews(user);
		Permission microphone=null,location=null;
		for(var permission:request.permissions()) {
			if(permission.code()==PermissionCode.MICROPHONE)microphone=permission.status();
			else location=permission.status();
		}
		repository.updatePermissions(user,microphone,location,now());
		save(session,"PERMISSIONS_UPDATE",key,request,user,null);
		return permissionViews(user);
	}
	public record DeletionResult(DeletionView view,String receiptToken,Instant receiptExpiresAt) {
		@Override public String toString(){return "DeletionResult[redacted]";}
	}
	private byte[] scope(Session session,String operation,Object request) {
		String kind="COLLECTION",target=switch(operation){case "PROFILE_UPDATE"->"profile";case "PERMISSIONS_UPDATE"->"permissions";case "SETTINGS_UPDATE"->"settings";default->"emergency-contacts";};
		if(operation.equals("CONTACT_UPDATE")||operation.equals("CONTACT_DELETE")){kind="CONTACT";target=((Map<?,?>)request).get("contactId").toString();}
		return crypto.idempotency("USER",session.userId(),kind,target);
	}
	private byte[] hash(Object request) {
		// Map의 JVM별 순회 순서가 재시작 후 멱등 요청 해시를 바꾸지 않게 한다.
		return crypto.hash("REQUEST",mapper.writeValueAsString(request instanceof Map<?,?> map ? new TreeMap<>(map) : request));
	}
	private Replay replay(Session session,String operation,UUID key,Object request) {
		Replay replay=auth.replay(scope(session,operation,request),operation,key);
		if (replay!=null) {
			if (!crypto.isEqual(hash(request),replay.requestHash())) throw new CustomException(ErrorCode.IDEMPOTENCY_CONFLICT);
			if (!"DONE".equals(replay.status())) throw new CustomException(ErrorCode.REQUEST_IN_PROGRESS,1);
		}
		return replay;
	}
	private void save(Session session,String operation,UUID key,Object request,UUID resource,Object response) {
		UUID id=UUID.randomUUID(); Instant now=now();
		auth.saveReplay(id,session,scope(session,operation,request),operation,key,hash(request),resource,
			response==null ? null : crypto.sealResponse(id.toString(),mapper.writeValueAsBytes(response)),response==null ? null : now.plusSeconds(60),now);
	}
}
