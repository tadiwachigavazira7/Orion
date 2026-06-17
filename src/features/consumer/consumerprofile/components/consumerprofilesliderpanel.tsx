import React, { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { colors, theme } from '../../../../theme';

interface ProfilePanelProps {
  firstName: string;
  setFirstName: (text: string) => void;
  lastName: string;
  setLastName: (text: string) => void;
}

export function ProfileSliderPanel({ firstName, setFirstName, lastName, setLastName }: ProfilePanelProps) {
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('james@example.com');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [password, setPassword] = useState('••••••••••••');

  const handleSaveChanges = () => {
    console.log('Saving profile data:', {
      firstName,
      lastName,
      username,
      email,
      phoneNumber,
      dateOfBirth,
      password,
    });
    alert('Changes saved successfully!');
  };

  return (
    <View style={styles.panelContainer}>
      <ScrollView 
        contentContainerStyle={styles.scrollContainer} 
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={styles.sectionHeading}>Your Profile</Text>

        <View style={styles.profileCard}>
          
          <View style={styles.row}>
            <Text style={styles.label}>First Name:</Text>
            <TextInput
              style={styles.input}
              value={firstName}
              onChangeText={setFirstName}
              placeholder="Enter first name"
              placeholderTextColor="#A3A3A3"
            />
          </View>

          <View style={styles.row}>
            <Text style={styles.label}>Last Name:</Text>
            <TextInput
              style={styles.input}
              value={lastName}
              onChangeText={setLastName}
              placeholder="Enter last name"
              placeholderTextColor="#A3A3A3"
            />
          </View>

          <View style={styles.row}>
            <Text style={styles.label}>Username:</Text>
            <TextInput
              style={styles.input}
              value={username}
              onChangeText={setUsername}
              placeholder="Enter username"
              placeholderTextColor="#A3A3A3"
              autoCapitalize="none"
            />
          </View>

          <View style={styles.row}>
            <Text style={styles.label}>Email:</Text>
            <TextInput
              style={styles.input}
              value={email}
              onChangeText={setEmail}
              keyboardType="email-address"
              autoCapitalize="none"
            />
          </View>

          <View style={styles.row}>
            <Text style={styles.label}>Phone Number:</Text>
            <TextInput
              style={styles.input}
              value={phoneNumber}
              onChangeText={setPhoneNumber}
              placeholder="Enter phone number"
              placeholderTextColor="#A3A3A3"
              keyboardType="phone-pad"
            />
          </View>

          <View style={styles.row}>
            <Text style={styles.label}>Date of Birth:</Text>
            <TextInput
              style={styles.input}
              value={dateOfBirth}
              onChangeText={setDateOfBirth}
              placeholder="YYYY-MM-DD"
              placeholderTextColor="#A3A3A3"
              autoCapitalize="none"
            />
          </View>

          <View style={[styles.row, styles.noBorder]}>
            <Text style={styles.label}>Password:</Text>
            <TextInput
              style={styles.input}
              value={password}
              onChangeText={setPassword}
              secureTextEntry={true}
              autoCapitalize="none"
            />
          </View>

        </View>

        <Pressable 
          onPress={handleSaveChanges} 
          style={({ pressed }) => [
            styles.saveButton, 
            pressed && { opacity: 0.8 }
          ]}
        >
          <Text style={styles.saveButtonText}>Save Changes</Text>
        </Pressable>

      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  panelContainer: {
    flex: 1,
    width: '100%', 
    backgroundColor: theme.background,
  },
  scrollContainer: {
    flexGrow: 1,
    alignItems: 'center',
    paddingTop: 5, 
    paddingHorizontal: 4,
    paddingBottom: 40,
  },
  sectionHeading: {
    fontSize: 20,
    fontWeight: '500',
    color: theme.text,
    marginTop: 10,
    marginBottom: 20,
    textAlign: 'center',
  },
  profileCard: {
    width: '100%',
    backgroundColor: '#EAEAEA', 
    borderRadius: 16,
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderWidth: 2,
    borderColor: theme.background,
    marginBottom: 35,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 14,
    borderBottomWidth: 1,
    borderBottomColor: '#CCCCCC', 
  },
  noBorder: {
    borderBottomWidth: 0,
  },
  label: {
    width: 115,
    fontSize: 15,
    fontWeight: '500',
    color: theme.text,
  },
  input: {
    flex: 1,
    fontSize: 15,
    color: theme.text,
    paddingVertical: 4,
  },
  saveButton: {
    backgroundColor: colors.dark.background,
    paddingVertical: 14,
    paddingHorizontal: 36,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  saveButtonText: {
    color: theme.background,
    fontSize: 16,
    fontWeight: '600',
    fontStyle: 'italic', 
  },
});